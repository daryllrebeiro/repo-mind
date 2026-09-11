package dev.repomind.core.rules

object RuleGenerator {

    /**
     * Infers the common base package from a collection of fully-qualified symbol/type names.
     * E.g. ["com.example.service.OrderService", "com.example.repo.OrderRepo"] -> "com.example"
     */
    fun inferBasePackage(fqns: Collection<String>): String? {
        val packages = fqns.mapNotNull { fqn ->
            val owner = fqn.substringBefore('#')
            val dot = owner.lastIndexOf('.')
            if (dot > 0) owner.substring(0, dot) else null
        }.filter { it.isNotBlank() }

        if (packages.isEmpty()) return null
        val splitList = packages.map { it.split('.') }
        val minLen = splitList.minOf { it.size }
        val commonPrefix = mutableListOf<String>()

        for (i in 0 until minLen) {
            val segment = splitList[0][i]
            if (splitList.all { it[i] == segment }) {
                commonPrefix.add(segment)
            } else {
                break
            }
        }

        return if (commonPrefix.size >= 2) commonPrefix.joinToString(".") else null
    }

    /**
     * Generates a complete, ready-to-save rules.yaml content string.
     */
    fun generateYaml(
        preset: ArchitecturePreset = ArchitecturePreset.THREE_TIER,
        basePackage: String? = null,
        includeHeaderComments: Boolean = true,
    ): String {
        val rules = ArchitecturePreset.generateRules(preset, basePackage)
        val sb = StringBuilder()

        if (includeHeaderComments) {
            sb.appendLine("# RepoMind Architecture Rules")
            sb.appendLine("# Preset: ${preset.displayName}")
            sb.appendLine("# ${preset.description}")
            if (!basePackage.isNullOrBlank()) {
                sb.appendLine("# Base Package: $basePackage")
            }
            sb.appendLine()
        }

        sb.appendLine("rules:")
        for (rule in rules) {
            sb.appendLine("  - name: ${rule.name}")
            if (!rule.description.isNullOrBlank()) {
                sb.appendLine("    description: ${rule.description}")
            }
            sb.appendLine("    from:")
            if (rule.from.namePattern != null) {
                val escaped = rule.from.namePattern.replace("\\", "\\\\")
                sb.appendLine("      namePattern: \"$escaped\"")
            }
            if (rule.from.annotations.isNotEmpty()) {
                sb.appendLine("      annotations: [${rule.from.annotations.joinToString(", ")}]")
            }
            sb.appendLine("    to:")
            if (rule.to.namePattern != null) {
                val escaped = rule.to.namePattern.replace("\\", "\\\\")
                sb.appendLine("      namePattern: \"$escaped\"")
            }
            if (rule.to.annotations.isNotEmpty()) {
                sb.appendLine("      annotations: [${rule.to.annotations.joinToString(", ")}]")
            }
            if (!rule.message.isNullOrBlank()) {
                sb.appendLine("    message: ${rule.message}")
            }
            sb.appendLine("    edgeKinds: [${rule.edgeKinds.joinToString(", ")}]")
            sb.appendLine()
        }

        return sb.toString().trimEnd() + "\n"
    }

    private fun Stereotype.toRegexSafePattern(): String =
        namePattern.orEmpty()
}
