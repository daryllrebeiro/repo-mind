package dev.repomind.core.report

import dev.repomind.core.graph.InMemoryGraph
import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind

data class TypeInfo(
    val fqn: String,
    val kind: String,
    val visibility: String,
    val annotations: List<String>,
)

class SmellDetectors(
    private val fanOutThreshold: Int = 20,
    private val fanInThreshold: Int = 15,
) {

    fun run(graph: InMemoryGraph, types: List<TypeInfo>): List<Finding> {
        val findings = mutableListOf<Finding>()
        findings += godClassFanOut(graph, types)
        findings += godClassFanIn(graph, types)
        findings += deadCode(graph, types)
        findings += cyclicPackages(graph)
        return findings
    }

    private fun testLike(fqn: String): Boolean =
        fqn.substringAfterLast('.').let { it.endsWith("Test") || it.endsWith("Tests") || it.endsWith("IT") }

    private fun godClassFanOut(graph: InMemoryGraph, types: List<TypeInfo>): List<Finding> =
        graph.adjacency.outgoing.entries
            .filter { !testLike(it.key) }
            .map { (fqn, edges) -> fqn to edges.count { it.kind == EdgeKind.CALLS } }
            .filter { it.second > fanOutThreshold }
            .sortedByDescending { it.second }
            .take(10)
            .map { (fqn, count) ->
                Finding(
                    detector = "god-class-fan-out",
                    severity = "warning",
                    summary = "$fqn makes $count distinct calls (threshold $fanOutThreshold)",
                    evidence = graph.adjacency.outgoing[fqn].orEmpty()
                        .filter { it.kind == EdgeKind.CALLS }
                        .take(5)
                        .map { "calls ${it.targetFqn}" },
                )
            }

    private fun godClassFanIn(graph: InMemoryGraph, types: List<TypeInfo>): List<Finding> =
        graph.adjacency.incoming.entries
            .filter { !testLike(it.key) }
            .map { (owner, edges) ->
                owner to edges.filter { it.kind == EdgeKind.CALLS }
                    .map { it.sourceFqn.substringBefore('#') }
                    .distinct()
            }
            .filter { it.second.size > fanInThreshold }
            .sortedByDescending { it.second.size }
            .take(10)
            .map { (fqn, callers) ->
                Finding(
                    detector = "god-class-fan-in",
                    severity = "warning",
                    summary = "$fqn is called directly by ${callers.size} types (threshold $fanInThreshold)",
                    evidence = callers.take(5).map { "called by $it" },
                )
            }

    private fun deadCode(graph: InMemoryGraph, types: List<TypeInfo>): List<Finding> {
        val entryAnnotations = setOf("RestController", "Controller", "SpringBootApplication", "Configuration")
        val candidates = types.filter { type ->
            type.visibility == "PUBLIC" &&
                !testLike(type.fqn) &&
                type.annotations.none { it in entryAnnotations } &&
                incomingStructuralCount(graph, type.fqn) == 0
        }
        if (candidates.isEmpty()) return emptyList()
        return listOf(
            Finding(
                detector = "dead-code",
                severity = "info",
                summary = "${candidates.size} public types have no incoming structural edges and are not entry points",
                evidence = candidates.take(15).map { it.fqn },
            ),
        )
    }

    private fun incomingStructuralCount(graph: InMemoryGraph, fqn: String): Int =
        graph.adjacency.incoming[fqn].orEmpty().count {
            it.kind in setOf(EdgeKind.CALLS, EdgeKind.USES, EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS)
        }

    fun cyclicPackages(graph: InMemoryGraph): List<Finding> {
        val packageEdges = mutableMapOf<String, MutableSet<String>>()
        for ((source, edges) in graph.adjacency.outgoing) {
            val fromPkg = source.substringBeforeLast('.', "")
            for (edge in edges) {
                if (edge.kind == EdgeKind.TESTS || edge.kind == EdgeKind.IMPORTS) continue
                val toPkg = edge.targetFqn.substringBefore('#').substringBeforeLast('.', "")
                if (fromPkg.isNotEmpty() && toPkg.isNotEmpty() && fromPkg != toPkg) {
                    packageEdges.getOrPut(fromPkg) { mutableSetOf() }.add(toPkg)
                }
            }
        }

        val sccs = TarjanScc.compute(packageEdges).filter { it.size > 1 }
        if (sccs.isEmpty()) return emptyList()

        return sccs.sortedByDescending { it.size }.take(10).map { cycle ->
            Finding(
                detector = "cyclic-packages",
                severity = "warning",
                summary = "package cycle of ${cycle.size} packages",
                evidence = cycle.sorted(),
            )
        }
    }
}

internal object TarjanScc {

    fun compute(graph: Map<String, Set<String>>): List<List<String>> {
        val index = HashMap<String, Int>()
        val lowlink = HashMap<String, Int>()
        val onStack = HashSet<String>()
        val stack = ArrayDeque<String>()
        val result = mutableListOf<List<String>>()
        var counter = 0

        fun strongConnect(v: String) {
            index[v] = counter
            lowlink[v] = counter
            counter++
            stack.addLast(v)
            onStack.add(v)

            for (w in graph[v].orEmpty()) {
                if (w !in index) {
                    strongConnect(w)
                    lowlink[v] = minOf(lowlink.getValue(v), lowlink.getValue(w))
                } else if (w in onStack) {
                    lowlink[v] = minOf(lowlink.getValue(v), index.getValue(w))
                }
            }

            if (lowlink.getValue(v) == index.getValue(v)) {
                val component = mutableListOf<String>()
                while (true) {
                    val w = stack.removeLast()
                    onStack.remove(w)
                    component.add(w)
                    if (w == v) break
                }
                result.add(component)
            }
        }

        for (v in graph.keys) {
            if (v !in index) strongConnect(v)
        }
        return result
    }
}
