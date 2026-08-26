package dev.repomind.core.report

import dev.repomind.core.model.code.EdgeKind

object MermaidEmitter {

    private const val MAX_ID_LENGTH = 60

    private class Ids {
        private val map = LinkedHashMap<String, String>()
        fun of(fqn: String): String = map.getOrPut(fqn) {
            "n" + map.size.toString() + "_" + fqn.replace(Regex("[^A-Za-z0-9]"), "_").take(MAX_ID_LENGTH)
        }
    }

    fun moduleDiagram(modules: List<String>, crossModuleEdges: List<Pair<String, String>>): String {
        val ids = Ids()
        return buildString {
            appendLine("```mermaid")
            appendLine("graph TD")
            for (module in modules) {
                appendLine("    ${ids.of(module)}[\"${sanitize(module)}\"]")
            }
            for ((from, to) in crossModuleEdges) {
                appendLine("    ${ids.of(from)} --> ${ids.of(to)}")
            }
            append("```")
        }
    }

    fun packageDiagram(
        packages: List<String>,
        edges: List<Triple<String, String, Int>>,
        minWeight: Int = 2,
        maxNodes: Int = 30,
        stereotypeLabels: Map<String, String> = emptyMap(),
    ): String {
        val pruned = edges.filter { it.third >= minWeight && it.first != it.second }
        val ranked = (pruned.map { it.first } + pruned.map { it.second }).distinct()
            .sortedByDescending { pkg -> pruned.filter { it.first == pkg || it.second == pkg }.sumOf { it.third } }
        val nodes = ranked.take(maxNodes)
        val nodeSet = nodes.toSet()
        val truncated = ranked.size > nodes.size
        val ids = Ids()

        return buildString {
            appendLine("```mermaid")
            appendLine("graph LR")
            for (pkg in nodes) {
                val label = stereotypeLabels[pkg]?.let { "$pkg<br/>$it" } ?: sanitize(pkg)
                appendLine("    ${ids.of(pkg)}[\"$label\"]")
            }
            for ((from, to, weight) in pruned) {
                if (from in nodeSet && to in nodeSet) {
                    appendLine("    ${ids.of(from)} -->|\"$weight\"| ${ids.of(to)}")
                }
            }
            if (truncated) {
                appendLine("    ${ids.of("<capped>")}[/\"... capped at $maxNodes packages by dependency weight ...\"/]")
            }
            append("```")
        }
    }

    fun flowDiagram(title: String, path: List<String>, dashedFromIndex: Int = Int.MAX_VALUE): String {
        val ids = Ids()
        return buildString {
            appendLine("```mermaid")
            appendLine("graph LR")
            for (participant in path.distinct()) {
                val cls = if (path.indexOf(participant) >= dashedFromIndex) ":::dashed" else ""
                appendLine("    ${ids.of(participant)}[\"${sanitize(participant.substringAfterLast('.'))}\"]$cls")
            }
            var previous = path.firstOrNull()
            for ((index, participant) in path.withIndex()) {
                if (index == 0 || participant == previous) continue
                appendLine("    ${ids.of(previous!!)} --> ${ids.of(participant)}")
                previous = participant
            }
            if (path.indices.any { it >= dashedFromIndex }) {
                appendLine("    classDef dashed stroke-dasharray: 5 5;")
            }
            append("```")
        }
    }

    internal fun sanitize(label: String): String =
        label.replace("\"", "'").replace("[", "(").replace("]", ")")

    fun kindLabel(kind: EdgeKind): String = when (kind) {
        EdgeKind.CALLS -> "calls"
        EdgeKind.USES -> "uses"
        EdgeKind.TESTS -> "tests"
        EdgeKind.IMPLEMENTS -> "implements"
        EdgeKind.EXTENDS -> "extends"
        EdgeKind.IMPORTS -> "imports"
    }
}
