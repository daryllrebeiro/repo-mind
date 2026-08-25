package dev.repomind.core.rules

import dev.repomind.core.model.code.DependencyEdge

class RuleEvaluator {

    fun evaluate(rules: List<RuleDef>, types: List<TypeStereotypeInfo>, edges: List<DependencyEdge>): RulesReport {
        val byFqn = types.associateBy { it.fqn }

        val violations = mutableListOf<Violation>()
        var evaluated = 0

        for (rule in rules) {
            evaluated++
            for (edge in edges) {
                val kindOk = edge.kind.name in rule.edgeKinds
                if (!kindOk) continue

                val sourceOwner = edge.sourceFqn.substringBefore('#')
                val targetOwner = edge.targetFqn.substringBefore('#')
                val sourceType = byFqn[sourceOwner] ?: continue
                val targetType = byFqn[targetOwner] ?: continue
                if (sourceOwner == targetOwner) continue

                if (matches(rule.from, sourceType) && matches(rule.to, targetType)) {
                    violations += Violation(
                        rule = rule.name,
                        message = rule.message,
                        sourceFqn = sourceOwner,
                        targetFqn = targetOwner,
                        edgeKind = edge.kind.name,
                    )
                }
            }
        }

        return RulesReport(
            evaluatedRules = evaluated,
            violations = violations.distinctBy { Triple(it.rule, it.sourceFqn, it.targetFqn) },
            checkedTypes = types.size,
        )
    }

    private fun matches(stereotype: Stereotype, type: TypeStereotypeInfo): Boolean {
        if (stereotype.isEmpty()) return false
        val annotationsOk = stereotype.annotations.isEmpty() || stereotype.annotations.any { it in type.annotations }
        val nameOk = stereotype.namePattern?.let { pattern ->
            Regex(pattern).matches(type.fqn.substringAfterLast('.'))
        } ?: true
        return annotationsOk && nameOk
    }
}
