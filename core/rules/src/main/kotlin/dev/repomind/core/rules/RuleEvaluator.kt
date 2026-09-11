package dev.repomind.core.rules

import dev.repomind.core.model.code.DependencyEdge

class RuleEvaluator {

    fun evaluate(
        rules: List<RuleDef>,
        types: List<TypeStereotypeInfo>,
        edges: List<DependencyEdge>,
        failOnViolation: Boolean = false,
        checkCycles: Boolean = false,
    ): RulesReport {
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
                        line = edge.line,
                    )
                }
            }
        }

        val cycleReport = if (checkCycles || rules.any { it.name.contains("cycle", ignoreCase = true) }) {
            val detector = CycleDetector()
            val rep = detector.detectPackageCycles(edges)
            for (cycle in rep.cycles) {
                violations += Violation(
                    rule = "no-package-cycles",
                    message = "Circular package dependency detected: ${cycle.path.joinToString(" -> ")}",
                    sourceFqn = cycle.path.first(),
                    targetFqn = cycle.path.getOrNull(1) ?: cycle.path.last(),
                    edgeKind = "CYCLE",
                    line = 0,
                )
            }
            rep
        } else {
            null
        }

        val report = RulesReport(
            evaluatedRules = evaluated,
            violations = violations.distinctBy { Triple(it.rule, it.sourceFqn, it.targetFqn) },
            checkedTypes = types.size,
            cycleReport = cycleReport,
        )

        if (failOnViolation && report.violations.isNotEmpty()) {
            throw ArchitectureRuleViolationException(report.violations)
        }

        return report
    }

    private fun matches(stereotype: Stereotype, type: TypeStereotypeInfo): Boolean {
        if (stereotype.isEmpty()) return false
        val annotationsOk = stereotype.annotations.isEmpty() || stereotype.annotations.any { it in type.annotations }
        val nameOk = stereotype.namePattern?.let { pattern ->
            val regex = Regex(pattern)
            regex.matches(type.fqn) || regex.containsMatchIn(type.fqn) || regex.matches(type.fqn.substringAfterLast('.'))
        } ?: true
        return annotationsOk && nameOk
    }
}

