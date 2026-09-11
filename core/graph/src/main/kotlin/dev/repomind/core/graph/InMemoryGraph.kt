package dev.repomind.core.graph

import dev.repomind.core.model.RepoMindLimits
import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind

enum class TraverseDirection { OUTGOING, INCOMING }

typealias InMemoryGraphStore = InMemoryGraph

open class InMemoryGraph(edges: Collection<DependencyEdge>) : GraphStore {

    data class Adjacency(val outgoing: Map<String, List<DependencyEdge>>, val incoming: Map<String, List<DependencyEdge>>) {
        val nodeCount: Int get() = outgoing.size.coerceAtLeast(incoming.size)
        val edgeCount: Int get() = outgoing.values.sumOf { it.size }
    }

    var adjacency: Adjacency
        private set

    private val edgeList = edges.toMutableList()

    init {
        adjacency = buildAdjacency(edgeList)
    }

    private fun buildAdjacency(edges: List<DependencyEdge>): Adjacency {
        val out = mutableMapOf<String, MutableList<DependencyEdge>>()
        val inc = mutableMapOf<String, MutableList<DependencyEdge>>()
        for (edge in edges) {
            out.getOrPut(edge.sourceFqn) { mutableListOf() }.add(edge)
            val targetOwner = edge.targetFqn.substringBefore('#')
            inc.getOrPut(targetOwner) { mutableListOf() }.add(edge)
        }
        return Adjacency(out, inc)
    }

    override fun addEdges(module: String, edges: Collection<DependencyEdge>, filePath: String) {
        edgeList.addAll(edges)
        adjacency = buildAdjacency(edgeList)
    }

    override fun replaceModule(moduleName: String, edges: List<DependencyEdge>): Int {
        edgeList.addAll(edges)
        adjacency = buildAdjacency(edgeList)
        return edges.size
    }

    override fun deleteModule(moduleName: String) {
        // In-memory edges don't currently tag module, so no-op unless explicitly rebuilt
    }

    override fun findDirectCallers(targetFqn: String, minConfidence: Confidence): List<DependencyEdge> {
        val owner = targetFqn.substringBefore('#')
        val edges = adjacency.incoming[owner].orEmpty() +
            (if ('#' in targetFqn) adjacency.incoming[targetFqn].orEmpty() else emptyList())
        return edges.filter { it.kind == EdgeKind.CALLS && (minConfidence == Confidence.POSSIBLE || it.confidence == Confidence.CONFIRMED) }
    }

    override fun findDirectCallees(sourceFqn: String, minConfidence: Confidence): List<DependencyEdge> {
        val owner = sourceFqn.substringBefore('#')
        val edges = adjacency.outgoing[owner].orEmpty() +
            (if ('#' in sourceFqn) adjacency.outgoing[sourceFqn].orEmpty() else emptyList())
        return edges.filter { it.kind == EdgeKind.CALLS && (minConfidence == Confidence.POSSIBLE || it.confidence == Confidence.CONFIRMED) }
    }

    fun directNeighbors(fqn: String, direction: TraverseDirection, kinds: Set<EdgeKind>): List<DependencyEdge> =
        when (direction) {
            TraverseDirection.OUTGOING -> adjacency.outgoing[fqn].orEmpty()
            TraverseDirection.INCOMING -> adjacency.incoming[fqn].orEmpty()
        }.filter { it.kind in kinds }

    fun transitive(
        start: String,
        direction: TraverseDirection,
        kinds: Set<EdgeKind>,
        maxDepth: Int = RepoMindLimits.DEFAULT_GRAPH_DEPTH,
        maxNodes: Int = RepoMindLimits.MAX_GRAPH_NODES,
    ): Set<String> {
        val visited = mutableSetOf(start)
        var frontier = listOf(start)
        var depth = 0
        while (frontier.isNotEmpty() && depth < maxDepth && visited.size < maxNodes) {
            frontier = frontier.flatMap { node ->
                val candidates = when (direction) {
                    TraverseDirection.OUTGOING ->
                        adjacency.outgoing[node].orEmpty().map { it.targetFqn }
                    TraverseDirection.INCOMING ->
                        adjacency.incoming[node].orEmpty().map { it.sourceFqn }
                }
                candidates.mapNotNull { nextRaw ->
                    if (visited.add(nextRaw)) nextRaw else null
                }
            }
            depth++
        }
        visited.remove(start)
        return visited
    }

    override fun transitiveCallers(fqn: String, maxDepth: Int): Set<String> =
        transitive(fqn, TraverseDirection.INCOMING, setOf(EdgeKind.CALLS), maxDepth = maxDepth)

    override fun transitiveCallees(fqn: String, maxDepth: Int): Set<String> =
        transitive(fqn, TraverseDirection.OUTGOING, setOf(EdgeKind.CALLS), maxDepth = maxDepth)

    override fun transitiveDependents(fqn: String): Set<String> =
        transitive(
            fqn,
            TraverseDirection.INCOMING,
            setOf(EdgeKind.CALLS, EdgeKind.USES, EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS),
        )

    override fun hasDynamicDispatch(fqn: String): Boolean {
        val owner = fqn.substringBefore('#')
        val edges = directNeighbors(fqn, TraverseDirection.OUTGOING, setOf(EdgeKind.CALLS)) +
            directNeighbors(owner, TraverseDirection.OUTGOING, setOf(EdgeKind.CALLS))
        return edges.any { "reflect" in it.targetFqn || it.targetFqn.startsWith("java.lang.reflect") }
    }

    override fun affectedTests(fqn: String): Set<String> {
        val affectedProduction = transitiveDependents(fqn) + fqn
        return buildSet {
            for (target in affectedProduction) {
                for (edge in adjacency.incoming[target].orEmpty()) {
                    if (edge.kind == EdgeKind.TESTS) add(edge.sourceFqn)
                }
            }
        }
    }

    fun testCoverage(fqn: String): Set<String> = affectedTests(fqn)

    override fun findRelatedTests(productionFqn: String): List<DependencyEdge> {
        val owner = productionFqn.substringBefore('#')
        return adjacency.incoming[owner].orEmpty().filter { it.kind == EdgeKind.TESTS }
    }

    override fun packageDependencies(): Map<String, Set<String>> {
        val map = mutableMapOf<String, MutableSet<String>>()
        for (edge in edgeList) {
            val srcOwner = edge.sourceFqn.substringBefore('#')
            val tgtOwner = edge.targetFqn.substringBefore('#')
            val srcPkg = srcOwner.substringBeforeLast('.').takeIf { '.' in srcOwner } ?: continue
            val tgtPkg = tgtOwner.substringBeforeLast('.').takeIf { '.' in tgtOwner } ?: continue
            if (srcPkg != tgtPkg) {
                map.getOrPut(srcPkg) { mutableSetOf() }.add(tgtPkg)
            }
        }
        return map
    }

    override fun moduleDependencies(typeToModule: Map<String, String>): Map<String, Set<String>> {
        val map = mutableMapOf<String, MutableSet<String>>()
        for (edge in edgeList) {
            val srcOwner = edge.sourceFqn.substringBefore('#')
            val tgtOwner = edge.targetFqn.substringBefore('#')
            val srcMod = typeToModule[srcOwner] ?: continue
            val tgtMod = typeToModule[tgtOwner] ?: continue
            if (srcMod != tgtMod) {
                map.getOrPut(srcMod) { mutableSetOf() }.add(tgtMod)
            }
        }
        return map
    }

    override fun allEdges(): List<DependencyEdge> = edgeList.toList()

    override fun count(): Long = edgeList.size.toLong()

    companion object {
        fun of(edges: Collection<DependencyEdge>): InMemoryGraph = InMemoryGraph(edges)
    }
}

val Confidence.weight: Int get() = when (this) {
    Confidence.CONFIRMED -> 1
    Confidence.POSSIBLE -> 0
}
