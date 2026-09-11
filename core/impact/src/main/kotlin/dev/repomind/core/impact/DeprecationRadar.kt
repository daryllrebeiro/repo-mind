package dev.repomind.core.impact

import dev.repomind.core.graph.GraphStore
import dev.repomind.core.model.code.Confidence
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class DeprecatedSymbolCandidate(
    val fqn: String,
    val kind: String, // CLASS, INTERFACE, METHOD, FIELD
    val module: String = "",
    val filePath: String? = null,
    val line: Int = 0,
    val annotations: List<String> = emptyList(),
    val javadoc: String? = null,
)

@Serializable
data class DeprecatedSymbolInfo(
    val fqn: String,
    val kind: String,
    val module: String,
    val filePath: String?,
    val line: Int,
    val replacementHint: String? = null,
    val directCallersCount: Int,
    val directCallers: List<String>,
    val blastRadiusCount: Int,
    val affectedTestsCount: Int,
    val effortScore: Int,
    val effortCategory: String, // LOW, MEDIUM, HIGH, CRITICAL
)

@Serializable
data class DeprecationRadarReport(
    val totalDeprecatedSymbols: Int,
    val totalDirectCallers: Int,
    val totalTransitiveImpact: Int,
    val overallDeprecationDebtScore: Int,
    val items: List<DeprecatedSymbolInfo>,
) {
    fun toMarkdown(): String {
        val sb = StringBuilder()
        sb.appendLine("# RepoMind Deprecation Radar & Semantic Drift Report")
        sb.appendLine()
        sb.appendLine("- **Overall Deprecation Debt Score**: $overallDeprecationDebtScore / 100")
        sb.appendLine("- **Total Deprecated Symbols**: $totalDeprecatedSymbols")
        sb.appendLine("- **Total Direct Callers to Migrate**: $totalDirectCallers")
        sb.appendLine("- **Transitive Blast Radius Impact**: $totalTransitiveImpact")
        sb.appendLine()
        sb.appendLine("## Migration Priority Matrix")
        sb.appendLine()
        sb.appendLine("| Symbol | Kind | Callers | Blast Radius | Replacement Hint | Effort | Level |")
        sb.appendLine("|---|---|---|---|---|---|---|")
        for (item in items) {
            val repl = item.replacementHint ?: "*(none specified)*"
            sb.appendLine("| `${item.fqn}` | ${item.kind} | ${item.directCallersCount} | ${item.blastRadiusCount} | $repl | ${item.effortScore} | **${item.effortCategory}** |")
        }
        sb.appendLine()

        if (items.isNotEmpty()) {
            sb.appendLine("## Migration Hotspots")
            for (item in items.take(5)) {
                sb.appendLine("### `${item.fqn}` (${item.effortCategory} - Score: ${item.effortScore})")
                if (item.filePath != null) {
                    sb.appendLine("- **Location**: `${item.filePath}:${item.line}`")
                }
                if (item.replacementHint != null) {
                    sb.appendLine("- **Recommended Replacement**: `${item.replacementHint}`")
                }
                sb.appendLine("- **Direct Callers (${item.directCallersCount})**:")
                if (item.directCallers.isEmpty()) {
                    sb.appendLine("  *(no direct callers found — candidate for safe deletion)*")
                } else {
                    item.directCallers.take(10).forEach { sb.appendLine("  - `$it`") }
                }
                sb.appendLine()
            }
        }
        return sb.toString()
    }
}

class DeprecationRadar(private val graph: GraphStore) {

    /**
     * Scans a collection of candidate symbols, filters for deprecated definitions,
     * and evaluates caller blast radius and migration effort scores.
     */
    fun scan(symbols: Collection<DeprecatedSymbolCandidate>): DeprecationRadarReport {
        val deprecatedCandidates = symbols.filter { sym ->
            sym.annotations.any { isDeprecatedAnnotation(it) } ||
                (sym.javadoc?.contains("@deprecated", ignoreCase = true) == true)
        }

        val items = deprecatedCandidates.map { sym ->
            val owner = sym.fqn.substringBefore('#')
            val incomingEdges = graph.findDirectCallers(sym.fqn) +
                (if ('#' in sym.fqn) emptyList() else graph.findDirectCallers(owner))

            val directCallers = incomingEdges.map { it.sourceFqn }.distinct().sorted()
            val blastRadius = graph.transitiveCallers(owner, 3)
            val affectedTests = graph.affectedTests(owner)

            val replacementHint = extractReplacementHint(sym)
            val (effortScore, effortCategory) = calculateEffort(
                kind = sym.kind,
                directCallersCount = directCallers.size,
                blastRadiusCount = blastRadius.size,
                affectedTestsCount = affectedTests.size,
                hasReplacementHint = replacementHint != null,
            )

            DeprecatedSymbolInfo(
                fqn = sym.fqn,
                kind = sym.kind,
                module = sym.module,
                filePath = sym.filePath,
                line = sym.line,
                replacementHint = replacementHint,
                directCallersCount = directCallers.size,
                directCallers = directCallers,
                blastRadiusCount = blastRadius.size,
                affectedTestsCount = affectedTests.size,
                effortScore = effortScore,
                effortCategory = effortCategory,
            )
        }.sortedByDescending { it.effortScore }

        val totalDirectCallers = items.sumOf { it.directCallersCount }
        val totalTransitive = items.sumOf { it.blastRadiusCount }
        val overallDebtScore = if (items.isEmpty()) 0 else {
            (items.map { it.effortScore }.average()).toInt().coerceIn(0, 100)
        }

        return DeprecationRadarReport(
            totalDeprecatedSymbols = items.size,
            totalDirectCallers = totalDirectCallers,
            totalTransitiveImpact = totalTransitive,
            overallDeprecationDebtScore = overallDebtScore,
            items = items,
        )
    }

    private fun isDeprecatedAnnotation(ann: String): Boolean {
        val clean = ann.trim().removePrefix("@")
        return clean.equals("Deprecated", ignoreCase = true) ||
            clean.startsWith("java.lang.Deprecated", ignoreCase = true) ||
            clean.startsWith("kotlin.Deprecated", ignoreCase = true) ||
            clean.contains("Deprecated")
    }

    fun extractReplacementHint(sym: DeprecatedSymbolCandidate): String? {
        // Check annotations for replacement expressions: e.g. replaceWith = ReplaceWith("..."), message = "..."
        for (ann in sym.annotations) {
            val replaceWithMatch = Regex("""replaceWith\s*=\s*ReplaceWith\(["']([^"']+)["']\)""").find(ann)
            if (replaceWithMatch != null) {
                return replaceWithMatch.groupValues[1].trim()
            }
            val useMatch = Regex("""(?i)use\s+([a-zA-Z0-9_#.]+)\s+instead""").find(ann)
            if (useMatch != null) {
                return useMatch.groupValues[1].trim()
            }
        }

        // Check Javadoc tag
        val doc = sym.javadoc
        if (!doc.isNullOrBlank()) {
            val cleanedDoc = doc.replace(Regex("""\{@link\s+([^}]+)\}""")) { it.groupValues[1].trim() }
            val seeMatch = Regex("""@see\s+([a-zA-Z0-9_#.]+)""").find(cleanedDoc)
            if (seeMatch != null) {
                return seeMatch.groupValues[1].trim()
            }
            val replacedBy = Regex("""(?i)@deprecated\s+replaced by\s+[`"'{]*([a-zA-Z0-9_#.]+)[`"'}]*""").find(cleanedDoc)
            if (replacedBy != null) {
                return replacedBy.groupValues[1].trim()
            }
            val javadocDep = Regex("""(?i)@deprecated\s+use\s+[`"'{]*([a-zA-Z0-9_#.]+)[`"'}]*\s+instead""").find(cleanedDoc)
            if (javadocDep != null) {
                return javadocDep.groupValues[1].trim()
            }
        }

        // Fallback: check file content around declaration line if file exists
        if (sym.filePath != null) {
            try {
                val path = Path.of(sym.filePath)
                if (Files.isRegularFile(path)) {
                    val lines = Files.readAllLines(path)
                    val startLine = (sym.line - 15).coerceAtLeast(0)
                    val endLine = (sym.line + 1).coerceAtMost(lines.size)
                    val snippet = lines.subList(startLine, endLine).joinToString("\n")
                    val useMatch = Regex("""(?i)@deprecated\s+use\s+[`"'{]*([a-zA-Z0-9_#.]+)[`"'}]*\s+instead""").find(snippet)
                        ?: Regex("""(?i)use\s+([a-zA-Z0-9_#.]+)\s+instead""").find(snippet)
                    if (useMatch != null) {
                        return useMatch.groupValues[1].trim()
                    }
                }
            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun calculateEffort(
        kind: String,
        directCallersCount: Int,
        blastRadiusCount: Int,
        affectedTestsCount: Int,
        hasReplacementHint: Boolean,
    ): Pair<Int, String> {
        var score = 10
        score += (directCallersCount * 5).coerceAtMost(40)
        score += (blastRadiusCount * 2).coerceAtMost(25)
        if (kind in setOf("CLASS", "INTERFACE")) {
            score += 15
        }
        if (affectedTestsCount == 0 && directCallersCount > 0) {
            score += 15
        }
        if (hasReplacementHint) {
            score -= 15
        }
        val clamped = score.coerceIn(1, 100)
        val category = when {
            clamped >= 75 -> "CRITICAL"
            clamped >= 50 -> "HIGH"
            clamped >= 25 -> "MEDIUM"
            else -> "LOW"
        }
        return clamped to category
    }
}
