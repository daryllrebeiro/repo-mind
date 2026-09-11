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
    val line: Int = 0,
)

class ArchitectureRuleViolationException(val violations: List<Violation>) :
    RuntimeException("Architecture check failed with ${violations.size} violation(s):\n" +
        violations.joinToString("\n") { "  - [${it.rule}] ${it.sourceFqn} (${if (it.line > 0) "line ${it.line}" else "unspecified"}) -> ${it.targetFqn} [${it.edgeKind}]: ${it.message ?: "Illegal dependency"}" })

@Serializable
data class RulesReport(
    val evaluatedRules: Int,
    val violations: List<Violation>,
    val checkedTypes: Int,
) {
    val passed: Boolean get() = violations.isEmpty()

    fun violationsBy(symbolFqn: String): List<Violation> =
        violations.filter { it.sourceFqn.substringBefore('#') == symbolFqn }
}
