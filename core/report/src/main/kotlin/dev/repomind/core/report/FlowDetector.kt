package dev.repomind.core.report

import dev.repomind.core.graph.InMemoryGraph
import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.EdgeKind

class FlowDetector(
    private val maxDepth: Int = 6,
    private val maxFlowsPerEntry: Int = 3,
    private val maxFlowsTotal: Int = 15,
) {

    fun detect(graph: InMemoryGraph, types: List<TypeInfo>): List<Flow> {
        val entryPoints = types.filter { type ->
            type.annotations.any { it in ENTRY_ANNOTATIONS }
        }.map { it.fqn }
        val projectTypes = types.map { it.fqn }.toSet()

        val flows = mutableListOf<Flow>()
        for (entry in entryPoints.sorted()) {
            var perEntry = 0
            var callPaths = longestPathsFrom(entry, graph, projectTypes, setOf(EdgeKind.CALLS))
            if (callPaths.isEmpty()) {
                callPaths = longestPathsFrom(entry, graph, projectTypes, setOf(EdgeKind.CALLS, EdgeKind.USES))
            }
            for (path in callPaths) {
                if (perEntry >= maxFlowsPerEntry) break
                if (path.participants.size < 2) continue
                flows += Flow(
                    name = path.participants.first().substringAfterLast('.') +
                        " -> " + path.participants.last().substringAfterLast('.'),
                    entryPoint = entry,
                    participants = path.participants,
                    dashedFromIndex = path.firstPossibleHop,
                )
                perEntry++
            }
        }
        return flows.distinctBy { it.participants }.take(maxFlowsTotal)
    }

    private data class Walk(val participants: List<String>, val firstPossibleHop: Int)

    private fun longestPathsFrom(entry: String, graph: InMemoryGraph, projectTypes: Set<String>, kinds: Set<EdgeKind>): List<Walk> {
        val walks = mutableListOf<Walk>()
        dfs(entry, graph, projectTypes, kinds, ArrayDeque(listOf(entry)), ArrayDeque(), walks)
        return walks.sortedWith(compareByDescending<Walk> { it.participants.size }.thenBy { walk -> walk.hashCode() })
    }

    private fun dfs(
        node: String,
        graph: InMemoryGraph,
        projectTypes: Set<String>,
        kinds: Set<EdgeKind>,
        nodes: ArrayDeque<String>,
        hops: ArrayDeque<Confidence>,
        out: MutableList<Walk>,
    ) {
        if (nodes.size >= maxDepth) {
            out.add(Walk(nodes.toList().trimTrailingExternal(projectTypes), firstPossible(hops)))
            return
        }
        val nexts = graph.adjacency.outgoing[node].orEmpty()
            .filter { it.kind in kinds }
            .map { it.targetFqn.substringBefore('#') to it.confidence }
            .distinctBy { it.first }
            .filter { (target, _) -> target !in nodes }
            .sortedWith(compareByDescending<Pair<String, Confidence>> { it.first in projectTypes }.thenBy { it.first })

        if (nexts.isEmpty()) {
            if (nodes.size >= 2) out.add(Walk(nodes.toList().trimTrailingExternal(projectTypes), firstPossible(hops)))
            return
        }
        for ((next, confidence) in nexts) {
            if (out.size >= maxFlowsPerEntry * 4) return
            nodes.addLast(next)
            hops.addLast(confidence)
            dfs(next, graph, projectTypes, kinds, nodes, hops, out)
            nodes.removeLast()
            hops.removeLast()
        }
    }

    private fun List<String>.trimTrailingExternal(projectTypes: Set<String>): List<String> =
        this.dropLastWhile { it !in projectTypes && this.size > 2 }

    private fun firstPossible(hops: ArrayDeque<Confidence>): Int {
        hops.forEachIndexed { index, c -> if (c == Confidence.POSSIBLE) return index + 1 }
        return Int.MAX_VALUE
    }

    companion object {
        val ENTRY_ANNOTATIONS = setOf("RestController", "Controller", "SpringBootApplication", "MessagingGateway", "KafkaListener")
    }
}
