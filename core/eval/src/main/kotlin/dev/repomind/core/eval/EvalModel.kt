package dev.repomind.core.eval

import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.EdgeKind
import kotlinx.serialization.Serializable

@Serializable
data class EdgeExpectation(
    val sourceFqn: String,
    val targetOwner: String,
    val kind: String = "CALLS",
    val minConfidence: String = "POSSIBLE",
)

@Serializable
data class EvalCase(
    val name: String,
    val description: String? = null,
    val expectations: List<EdgeExpectation>,
)

@Serializable
data class CaseResult(
    val caseName: String,
    val matched: Int,
    val missed: Int,
    val spurious: Int,
    val precision: Double,
    val recall: Double,
    val missedDetails: List<String> = emptyList(),
) {
    fun isPerfect(): Boolean = missed == 0 && spurious == 0
}

@Serializable
data class EvalReport(
    val caseResults: List<CaseResult>,
    val macroPrecision: Double,
    val macroRecall: Double,
    val totalEdgesEvaluated: Int,
) {
    fun summary(): String =
        "cases=${caseResults.size} precision=%.3f recall=%.3f edges=%d".format(macroPrecision, macroRecall, totalEdgesEvaluated)
}
