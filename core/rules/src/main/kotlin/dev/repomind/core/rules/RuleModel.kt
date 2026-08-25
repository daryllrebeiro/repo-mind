package dev.repomind.core.rules

import kotlinx.serialization.Serializable

data class Stereotype(
    val annotations: List<String> = emptyList(),
    val namePattern: String? = null,
) {
    fun isEmpty(): Boolean = annotations.isEmpty() && namePattern == null
}

data class RuleDef(
    val name: String,
    val description: String? = null,
    val from: Stereotype,
    val to: Stereotype,
    val message: String? = null,
    val edgeKinds: List<String> = listOf("CALLS", "USES"),
)

@Serializable
data class TypeStereotypeInfo(
    val fqn: String,
    val annotations: List<String>,
)

@Serializable
data class Violation(
    val rule: String,
    val message: String?,
    val sourceFqn: String,
    val targetFqn: String,
    val edgeKind: String,
)

@Serializable
data class RulesReport(
    val evaluatedRules: Int,
    val violations: List<Violation>,
    val checkedTypes: Int,
) {
    fun violationsBy(symbolFqn: String): List<Violation> =
        violations.filter { it.sourceFqn.substringBefore('#') == symbolFqn }
}
