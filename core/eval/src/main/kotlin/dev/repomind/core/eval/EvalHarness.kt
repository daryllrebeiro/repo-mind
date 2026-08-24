package dev.repomind.core.eval

import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind

object EdgeMatcher {

    private val confidenceRank = mapOf(Confidence.POSSIBLE to 0, Confidence.CONFIRMED to 1)

    fun ownerOf(targetFqn: String): String =
        targetFqn.substringBefore('#')

    fun matches(edge: DependencyEdge, expectation: EdgeExpectation): Boolean {
        val expectedKind = runCatching { EdgeKind.valueOf(expectation.kind) }.getOrNull() ?: return false
        if (edge.kind != expectedKind) return false
        if (edge.sourceFqn != expectation.sourceFqn) return false
        if (ownerOf(edge.targetFqn) != expectation.targetOwner) return false

        val expectedFloor = runCatching { Confidence.valueOf(expectation.minConfidence) }.getOrDefault(Confidence.POSSIBLE)
        return confidenceRank.getValue(edge.confidence) >= confidenceRank.getValue(expectedFloor)
    }
}

class EvalHarness {

    fun run(edges: List<DependencyEdge>, cases: List<EvalCase>): EvalReport {
        val results = cases.map { evaluateCase(edges, it) }
        val macroPrecision = results.map { it.precision }.average().takeIf { it.isFinite() } ?: 0.0
        val macroRecall = results.map { it.recall }.average().takeIf { it.isFinite() } ?: 0.0
        return EvalReport(
            caseResults = results,
            macroPrecision = macroPrecision,
            macroRecall = macroRecall,
            totalEdgesEvaluated = edges.size,
        )
    }

    private fun evaluateCase(edges: List<DependencyEdge>, evalCase: EvalCase): CaseResult {
        var matched = 0
        val missedDetails = mutableListOf<String>()
        for (expectation in evalCase.expectations) {
            val hit = edges.any { EdgeMatcher.matches(it, expectation) }
            if (hit) {
                matched++
            } else {
                missedDetails += "${expectation.sourceFqn} -> ${expectation.targetOwner} (${expectation.kind}/${expectation.minConfidence})"
            }
        }
        val total = evalCase.expectations.size
        val missed = total - matched
        val spurious = countSpurious(edges, evalCase)
        val precision = if (matched + spurious == 0) 1.0 else matched.toDouble() / (matched + spurious)
        val recall = if (total == 0) 1.0 else matched.toDouble() / total
        return CaseResult(
            caseName = evalCase.name,
            matched = matched,
            missed = missed,
            spurious = spurious,
            precision = precision,
            recall = recall,
            missedDetails = missedDetails,
        )
    }

    private fun countSpurious(edges: List<DependencyEdge>, evalCase: EvalCase): Int {
        val relevantSources = evalCase.expectations.map { it.sourceFqn }.toSet()
        val expectedPairs = evalCase.expectations.map { it.sourceFqn to it.targetOwner }.toSet()
        return edges.count { edge ->
            edge.sourceFqn in relevantSources &&
                edge.kind == EdgeKind.CALLS &&
                (edge.sourceFqn to EdgeMatcher.ownerOf(edge.targetFqn)) !in expectedPairs
        }
    }
}
