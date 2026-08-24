package dev.repomind.core.graph

import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind

enum class TraverseDirection { OUTGOING, INCOMING }

class InMemoryGraph(edges: Collection<DependencyEdge>) {

    data class Adjacency(val outgoing: Map<String, List<DependencyEdge>>, val incoming: Map<String, List<DependencyEdge>>) {
        val nodeCount: Int get() = outgoing.size.coerceAtLeast(incoming.size)
        val edgeCount: Int get() = outgoing.values.sumOf { it.size }
    }

    val adjacency: Adjacency

    init {
        val out = mutableMapOf<String, MutableList<DependencyEdge>>()
        val inc = mutableMapOf<String, MutableList<DependencyEdge>>()
        for (edge in edges) {
            out.getOrPut(edge.sourceFqn) { mutableListOf() }.add(edge)
            val targetOwner = edge.targetFqn.substringBefore('#')
            inc.getOrPut(targetOwner) { mutableListOf() }.add(edge)
        }
        adjacency = Adjacency(out, inc)
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
        maxDepth: Int = Int.MAX_VALUE,
        maxNodes: Int = 100_000,
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

    fun transitiveCallers(fqn: String, maxDepth: Int = Int.MAX_VALUE): Set<String> =
        transitive(fqn, TraverseDirection.INCOMING, setOf(EdgeKind.CALLS), maxDepth = maxDepth)

    fun transitiveDependents(fqn: String): Set<String> =
        transitive(
            fqn,
            TraverseDirection.INCOMING,
            setOf(EdgeKind.CALLS, EdgeKind.USES, EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS),
        )

    fun affectedTests(fqn: String): Set<String> {
        val affectedProduction = transitiveDependents(fqn) + fqn
        return buildSet {
            for (target in affectedProduction) {
                for (edge in adjacency.incoming[target].orEmpty()) {
                    if (edge.kind == EdgeKind.TESTS) add(edge.sourceFqn)
                }
            }
        }
    }

    companion object {
        fun of(edges: Collection<DependencyEdge>): InMemoryGraph = InMemoryGraph(edges)
    }
}

val Confidence.weight: Int get() = when (this) {
    Confidence.CONFIRMED -> 1
    Confidence.POSSIBLE -> 0
}
