package dev.repomind.core.rules

enum class ArchitecturePreset(val id: String, val displayName: String, val description: String) {
    HEXAGONAL(
        id = "hexagonal",
        displayName = "Hexagonal Architecture (Ports & Adapters)",
        description = "Enforces strict isolation of the core domain from application services and inbound/outbound adapters.",
    ),
    CLEAN(
        id = "clean",
        displayName = "Clean Architecture",
        description = "Enforces the Dependency Rule: dependencies point inward from external frameworks toward business entities.",
    ),
    THREE_TIER(
        id = "three-tier",
        displayName = "Three-Tier Layered Architecture",
        description = "Classic Presentation, Service (Business), and Repository (Data) separation of concerns.",
    );

    companion object {
        fun fromId(id: String): ArchitecturePreset? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }

        /**
         * Generates a list of [RuleDef] architectural rules for the given preset.
         * If [basePackage] is provided (e.g. "com.example"), rules are scoped to that prefix;
         * otherwise, rules use universal regex patterns matching the layer package segments.
         */
        fun generateRules(preset: ArchitecturePreset, basePackage: String? = null): List<RuleDef> {
            val prefix = if (!basePackage.isNullOrBlank()) {
                "${basePackage.trimEnd('.').replace(".", "\\.")}\\.(.*\\.)?"
            } else {
                "(.*\\.)?"
            }

            return when (preset) {
                HEXAGONAL -> listOf(
                    RuleDef(
                        name = "hexagonal-domain-isolation",
                        description = "Domain core must not depend on Application services, Adapters, or Infrastructure",
                        from = Stereotype(namePattern = "${prefix}domain(\\..*)?"),
                        to = Stereotype(namePattern = "${prefix}(application|adapter|adapters|infrastructure|infra)(\\..*)?"),
                        message = "Domain layer must remain independent of external application and adapter layers",
                    ),
                    RuleDef(
                        name = "hexagonal-application-isolation",
                        description = "Application layer must not depend on concrete Adapters or Infrastructure",
                        from = Stereotype(namePattern = "${prefix}application(\\..*)?"),
                        to = Stereotype(namePattern = "${prefix}(adapter|adapters|infrastructure|infra)(\\..*)?"),
                        message = "Application use-cases must depend on ports (interfaces), not concrete adapters or infrastructure",
                    ),
                    RuleDef(
                        name = "hexagonal-adapter-inbound-isolation",
                        description = "Inbound adapters (e.g., controllers) must not depend on outbound adapters (e.g., persistence)",
                        from = Stereotype(namePattern = "${prefix}adapter(s)?\\.in(bound)?(\\..*)?"),
                        to = Stereotype(namePattern = "${prefix}adapter(s)?\\.out(bound)?(\\..*)?"),
                        message = "Inbound adapters should communicate through application ports, not call outbound adapters directly",
                    ),
                )

                CLEAN -> listOf(
                    RuleDef(
                        name = "clean-entities-independence",
                        description = "Enterprise business entities must not depend on use-cases, controllers, or frameworks",
                        from = Stereotype(namePattern = "${prefix}(entity|entities|domain)(\\..*)?"),
                        to = Stereotype(namePattern = "${prefix}(usecase|usecases|controller|controllers|presenter|gateway|db|framework)(\\..*)?"),
                        message = "Entities must not have dependencies on outer layers (Clean Architecture Dependency Rule)",
                    ),
                    RuleDef(
                        name = "clean-usecases-independence",
                        description = "Use-cases must not depend on UI controllers, presenters, or external frameworks",
                        from = Stereotype(namePattern = "${prefix}(usecase|usecases)(\\..*)?"),
                        to = Stereotype(namePattern = "${prefix}(controller|controllers|presenter|ui|framework)(\\..*)?"),
                        message = "Use-cases must not depend on delivery mechanisms or external framework details",
                    ),
                )

                THREE_TIER -> listOf(
                    RuleDef(
                        name = "three-tier-repo-isolation",
                        description = "Repositories must not depend on Service or Presentation layers",
                        from = Stereotype(namePattern = "${prefix}(repository|repositories|dao|persistence)(\\..*)?"),
                        to = Stereotype(namePattern = "${prefix}(service|services|controller|controllers|web|api)(\\..*)?"),
                        message = "Data access layer must not depend on higher-level service or presentation layers",
                    ),
                    RuleDef(
                        name = "three-tier-service-isolation",
                        description = "Service layer must not depend on Presentation / Web controllers",
                        from = Stereotype(namePattern = "${prefix}(service|services)(\\..*)?"),
                        to = Stereotype(namePattern = "${prefix}(controller|controllers|web|api)(\\..*)?"),
                        message = "Business service layer must not depend on presentation controllers",
                    ),
                    RuleDef(
                        name = "three-tier-controller-to-repo-bypass",
                        description = "Controllers must not bypass services to call repositories directly",
                        from = Stereotype(namePattern = "${prefix}(controller|controllers|web|api)(\\..*)?"),
                        to = Stereotype(namePattern = "${prefix}(repository|repositories|dao|persistence)(\\..*)?"),
                        message = "Presentation layer should delegate to service layer rather than calling repositories directly",
                    ),
                )
            }
        }
    }
}
