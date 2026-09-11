package dev.repomind.core.rules

import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

@Serializable
data class AdrDocument(
    val id: String,
    val title: String,
    val status: String = "Proposed",
    val date: String = LocalDate.now().toString(),
    val context: String,
    val decision: String,
    val consequencesPositive: List<String>,
    val consequencesNegative: List<String>,
    val rule: RuleDef,
    val yamlSnippet: String,
) {
    fun toMarkdown(): String {
        val sb = StringBuilder()
        sb.appendLine("# $id: $title")
        sb.appendLine()
        sb.appendLine("- **Status**: $status")
        sb.appendLine("- **Date**: $date")
        sb.appendLine("- **Deciders**: RepoMind Architectural Mining Engine")
        sb.appendLine()
        sb.appendLine("## Context")
        sb.appendLine(context.trim())
        sb.appendLine()
        sb.appendLine("## Decision")
        sb.appendLine(decision.trim())
        sb.appendLine()
        sb.appendLine("## Consequences")
        sb.appendLine("### Positive")
        for (item in consequencesPositive) {
            sb.appendLine("- $item")
        }
        sb.appendLine()
        sb.appendLine("### Negative")
        for (item in consequencesNegative) {
            sb.appendLine("- $item")
        }
        sb.appendLine()
        sb.appendLine("## RepoMind Architecture Rule")
        sb.appendLine("```yaml")
        sb.append(yamlSnippet.trimEnd())
        sb.appendLine()
        sb.appendLine("```")
        return sb.toString()
    }
}

object AdrGenerator {

    /**
     * Mines structural patterns and dependency conventions from the indexed graph,
     * producing drafted Architecture Decision Records (ADRs) with corresponding RepoMind rules.
     */
    fun generateAdrs(
        types: Collection<TypeStereotypeInfo>,
        edges: Collection<DependencyEdge>,
        basePackage: String? = null,
    ): List<AdrDocument> {
        val resolvedBasePackage = basePackage ?: RuleGenerator.inferBasePackage(types.map { it.fqn })
        val adrs = mutableListOf<AdrDocument>()
        var adrCounter = 1

        fun nextId(): String = "ADR-%04d".format(adrCounter++)

        // Pattern 1: Presentation / Web vs Persistence / Repository Isolation
        minePresentationPersistenceAdr(types, edges, resolvedBasePackage, ::nextId)?.let { adrs.add(it) }

        // Pattern 2: Domain Model Independence from Infrastructure
        mineDomainInfrastructureAdr(types, edges, resolvedBasePackage, ::nextId)?.let { adrs.add(it) }

        // Pattern 3: Acyclic Package Hierarchy
        mineAcyclicPackageAdr(edges, ::nextId)?.let { adrs.add(it) }

        // Pattern 4: Concealment of Internal Subsystem Implementations
        mineInternalEncapsulationAdr(types, edges, resolvedBasePackage, ::nextId)?.let { adrs.add(it) }

        // Pattern 5: Unidirectional Package Conventions
        mineUnidirectionalConventions(types, edges, resolvedBasePackage, ::nextId).forEach { adrs.add(it) }

        return adrs
    }

    /**
     * Writes generated ADR documents to the specified directory in markdown format.
     */
    fun writeAdrs(adrs: List<AdrDocument>, outputDir: Path): List<Path> {
        Files.createDirectories(outputDir)
        return adrs.map { adr ->
            val safeTitle = adr.title.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            val fileName = "${adr.id.lowercase()}-$safeTitle.md"
            val filePath = outputDir.resolve(fileName)
            Files.writeString(filePath, adr.toMarkdown())
            filePath
        }
    }

    private fun minePresentationPersistenceAdr(
        types: Collection<TypeStereotypeInfo>,
        edges: Collection<DependencyEdge>,
        basePackage: String?,
        idGen: () -> String,
    ): AdrDocument? {
        val webPattern = Regex("(?i).*(Controller|Endpoint|Resource|Handler|Web|Api).*")
        val repoPattern = Regex("(?i).*(Repository|Dao|Mapper|Store).*")

        val webTypes = types.filter { it.fqn.matches(webPattern) || it.annotations.any { a -> a.contains("Controller") || a.contains("Endpoint") } }
        val repoTypes = types.filter { it.fqn.matches(repoPattern) || it.annotations.any { a -> a.contains("Repository") || a.contains("Dao") } }

        if (webTypes.isEmpty() || repoTypes.isEmpty()) return null

        val webFqns = webTypes.map { it.fqn }.toSet()
        val repoFqns = repoTypes.map { it.fqn }.toSet()

        val directCalls = edges.filter { edge ->
            val srcOwner = edge.sourceFqn.substringBefore('#')
            val tgtOwner = edge.targetFqn.substringBefore('#')
            webFqns.contains(srcOwner) && repoFqns.contains(tgtOwner)
        }

        val prefix = if (!basePackage.isNullOrBlank()) "${basePackage.replace(".", "\\.")}\\..*" else ".*"
        val rule = RuleDef(
            name = "no-presentation-to-persistence",
            description = "Controllers and API endpoints must not directly access persistence repositories",
            from = Stereotype(namePattern = "$prefix(Controller|Endpoint|Resource|Web|Api).*"),
            to = Stereotype(namePattern = "$prefix(Repository|Dao|Mapper|Store).*"),
            message = "Presentation layer must access persistence exclusively via business service interfaces.",
            edgeKinds = listOf("CALLS", "USES"),
        )

        val context = if (directCalls.isEmpty()) {
            "Analysis of ${webTypes.size} presentation components and ${repoTypes.size} persistence components " +
                "revealed 0 direct couplings across ${edges.size} dependency edges. The codebase strictly routes requests through intermediate services."
        } else {
            "Analysis of ${webTypes.size} presentation components and ${repoTypes.size} persistence components " +
                "revealed ${directCalls.size} direct dependency coupling(s) that bypass the service layer."
        }

        val decision = "Presentation layers (Controllers, REST Endpoints, API Handlers) must never directly invoke " +
            "Repositories, DAOs, or data-mapping components. All persistence access must be mediated through domain or application services."

        return AdrDocument(
            id = idGen(),
            title = "Encapsulate Persistence Access within Service Layer",
            status = if (directCalls.isEmpty()) "Accepted" else "Proposed",
            context = context,
            decision = decision,
            consequencesPositive = listOf(
                "Ensures strict separation of concerns between HTTP/transport layer and database persistence.",
                "Enables central enforcement of transactional boundaries, caching, and security authorization in services.",
                "Prevents database schema changes from leaking into presentation contracts.",
            ),
            consequencesNegative = listOf(
                "Requires lightweight passthrough methods in services for simple CRUD operations.",
            ),
            rule = rule,
            yamlSnippet = formatRuleYaml(rule),
        )
    }

    private fun mineDomainInfrastructureAdr(
        types: Collection<TypeStereotypeInfo>,
        edges: Collection<DependencyEdge>,
        basePackage: String?,
        idGen: () -> String,
    ): AdrDocument? {
        val domainPattern = Regex("(?i).*\\.(domain|model|entity)(\\..*)?")
        val infraPattern = Regex("(?i).*\\.(infrastructure|infra|adapters?|persistence|database|db|web)(\\..*)?")

        val domainTypes = types.filter { it.fqn.matches(domainPattern) }
        val infraTypes = types.filter { it.fqn.matches(infraPattern) }

        if (domainTypes.isEmpty() || infraTypes.isEmpty()) return null

        val domainFqns = domainTypes.map { it.fqn }.toSet()
        val infraFqns = infraTypes.map { it.fqn }.toSet()

        val violations = edges.filter { edge ->
            val srcOwner = edge.sourceFqn.substringBefore('#')
            val tgtOwner = edge.targetFqn.substringBefore('#')
            domainFqns.contains(srcOwner) && infraFqns.contains(tgtOwner)
        }

        val prefix = if (!basePackage.isNullOrBlank()) "${basePackage.replace(".", "\\.")}\\." else ".*\\."
        val rule = RuleDef(
            name = "domain-must-not-depend-on-infrastructure",
            description = "Domain models and business logic must not depend on infrastructure or external adapters",
            from = Stereotype(namePattern = "${prefix}(domain|model|entity)(\\..*)?"),
            to = Stereotype(namePattern = "${prefix}(infrastructure|infra|adapters?|persistence|database|db|web)(\\..*)?"),
            message = "Domain entities must remain clean and technology-agnostic.",
            edgeKinds = listOf("CALLS", "USES"),
        )

        val context = "The repository contains ${domainTypes.size} domain/model classes and ${infraTypes.size} infrastructure components. " +
            "Observed dependencies from domain to infrastructure: ${violations.size}."

        val decision = "Domain models and core business entities must remain independent of external technologies, frameworks, " +
            "databases, and delivery mechanisms. Dependencies must point inward toward domain abstractions."

        return AdrDocument(
            id = idGen(),
            title = "Domain Model Independence from Infrastructure and External Delivery",
            status = if (violations.isEmpty()) "Accepted" else "Proposed",
            context = context,
            decision = decision,
            consequencesPositive = listOf(
                "Maximizes testability of business logic without mocking external infrastructure.",
                "Enables swapping database, cloud provider, or transport layer without modifying domain logic.",
                "Adheres to Clean Architecture and Hexagonal Architecture principles.",
            ),
            consequencesNegative = listOf(
                "Requires dependency inversion (ports and adapters) and mapping between domain entities and DTOs/ORM entities.",
            ),
            rule = rule,
            yamlSnippet = formatRuleYaml(rule),
        )
    }

    private fun mineAcyclicPackageAdr(
        edges: Collection<DependencyEdge>,
        idGen: () -> String,
    ): AdrDocument? {
        val distinctPackages = edges.flatMap { listOf(it.sourceFqn, it.targetFqn) }
            .mapNotNull { fqn ->
                val owner = fqn.substringBefore('#')
                val dot = owner.lastIndexOf('.')
                if (dot > 0) owner.substring(0, dot) else null
            }.filter { it.isNotBlank() }.toSet()
        if (distinctPackages.size < 2) return null

        val cycleReport = CycleDetector().detectPackageCycles(edges.toList())
        val hasCycles = cycleReport.hasCycles
        val context = if (!hasCycles) {
            "Analysis of ${distinctPackages.size} packages across ${edges.size} dependency edges revealed " +
                "0 circular package dependencies (0 strongly connected components). The package hierarchy is strictly directed and acyclic."
        } else {
            "Analysis revealed ${cycleReport.cycles.size} circular package dependency group(s) among ${distinctPackages.size} packages: " +
                cycleReport.cycles.joinToString("; ") { it.path.joinToString(" -> ") }
        }

        val decision = "The repository must maintain a strictly acyclic package structure (Directed Acyclic Graph). " +
            "Bidirectional or cyclic dependencies between packages are forbidden."

        val rule = RuleDef(
            name = "prohibit-package-cycles",
            description = "Packages must form a directed acyclic graph (no bidirectional package cycles)",
            from = Stereotype(namePattern = ".*"),
            to = Stereotype(namePattern = ".*"),
            message = "Package cycle detected; decompose or apply dependency inversion.",
            edgeKinds = listOf("CALLS", "USES"),
        )

        return AdrDocument(
            id = idGen(),
            title = "Strict Prohibition of Circular Package Dependencies",
            status = if (!hasCycles) "Accepted" else "Proposed",
            context = context,
            decision = decision,
            consequencesPositive = listOf(
                "Eliminates tight coupling and spaghetti dependencies between modules.",
                "Simplifies incremental compilation and independent module testing.",
                "Enables effortless extraction of reusable packages or submodules in the future.",
            ),
            consequencesNegative = listOf(
                "May require introducing shared interface packages or event dispatchers to decouple mutually dependent modules.",
            ),
            rule = rule,
            yamlSnippet = formatRuleYaml(rule),
        )
    }

    private fun mineInternalEncapsulationAdr(
        types: Collection<TypeStereotypeInfo>,
        edges: Collection<DependencyEdge>,
        basePackage: String?,
        idGen: () -> String,
    ): AdrDocument? {
        val internalTypes = types.filter { it.fqn.contains(".internal.") || it.fqn.contains(".impl.") }
        if (internalTypes.isEmpty()) return null

        val prefix = if (!basePackage.isNullOrBlank()) "${basePackage.replace(".", "\\.")}\\." else ".*\\."
        val rule = RuleDef(
            name = "no-cross-package-internal-access",
            description = "Internal implementation classes must not be accessed from outside their parent subsystem",
            from = Stereotype(namePattern = ".*"),
            to = Stereotype(namePattern = "${prefix}.*\\.(internal|impl)\\..*"),
            message = "Access to internal implementation package is restricted; consume public API interfaces instead.",
            edgeKinds = listOf("CALLS", "USES"),
        )

        val context = "The codebase defines ${internalTypes.size} classes inside '.internal.' or '.impl.' packages, " +
            "intended as encapsulation boundaries for private module details."

        val decision = "Internal implementation packages (matching '.internal.' or '.impl.') must be treated as private to their enclosing module. " +
            "External callers must consume public interfaces and factory entry points."

        return AdrDocument(
            id = idGen(),
            title = "Encapsulation of Internal Subsystem Implementations",
            status = "Proposed",
            context = context,
            decision = decision,
            consequencesPositive = listOf(
                "Hides non-public implementation details, allowing refactoring without breaking callers.",
                "Clearly defines the stable public API surface of each module.",
            ),
            consequencesNegative = listOf(
                "Requires defining explicit public interfaces and factory/provider classes.",
            ),
            rule = rule,
            yamlSnippet = formatRuleYaml(rule),
        )
    }

    private fun mineUnidirectionalConventions(
        types: Collection<TypeStereotypeInfo>,
        edges: Collection<DependencyEdge>,
        basePackage: String?,
        idGen: () -> String,
    ): List<AdrDocument> {
        val adrs = mutableListOf<AdrDocument>()
        val pkgCounts = mutableMapOf<Pair<String, String>, Int>()

        fun extractPackage(fqn: String): String {
            val owner = fqn.substringBefore('#')
            val dot = owner.lastIndexOf('.')
            return if (dot > 0) owner.substring(0, dot) else owner
        }

        for (edge in edges) {
            val srcPkg = extractPackage(edge.sourceFqn)
            val tgtPkg = extractPackage(edge.targetFqn)
            if (srcPkg != tgtPkg && srcPkg.isNotBlank() && tgtPkg.isNotBlank()) {
                val pair = srcPkg to tgtPkg
                pkgCounts[pair] = (pkgCounts[pair] ?: 0) + 1
            }
        }

        // Look for strong unidirectional dependencies where A -> B >= 3 edges and B -> A == 0
        for ((pair, forwardCount) in pkgCounts) {
            if (forwardCount >= 3) {
                val reverseCount = pkgCounts[pair.second to pair.first] ?: 0
                if (reverseCount == 0 && adrs.size < 2) {
                    val srcPkgEscaped = pair.first.replace(".", "\\.")
                    val tgtPkgEscaped = pair.second.replace(".", "\\.")

                    val rule = RuleDef(
                        name = "unidirectional-${pair.first.substringAfterLast('.')}-to-${pair.second.substringAfterLast('.')}",
                        description = "Enforce unidirectional flow: ${pair.first} may depend on ${pair.second}, but reverse is forbidden",
                        from = Stereotype(namePattern = "$tgtPkgEscaped(\\..*)?"),
                        to = Stereotype(namePattern = "$srcPkgEscaped(\\..*)?"),
                        message = "Illegal backward dependency from ${pair.second} to ${pair.first}.",
                        edgeKinds = listOf("CALLS", "USES"),
                    )

                    adrs.add(
                        AdrDocument(
                            id = idGen(),
                            title = "Unidirectional Dependency Flow from ${pair.first.substringAfterLast('.')} to ${pair.second.substringAfterLast('.')}",
                            status = "Accepted",
                            context = "Observed $forwardCount dependency edge(s) from package '${pair.first}' to '${pair.second}', " +
                                "with 0 reverse edges. This establishes a clean, strictly unidirectional subsystem flow.",
                            decision = "Package '${pair.second}' must never depend on or import package '${pair.first}'.",
                            consequencesPositive = listOf(
                                "Prevents accidental back-dependencies from forming over time.",
                                "Maintains clean, layered modularity.",
                            ),
                            consequencesNegative = listOf(
                                "Requires dependency inversion if reverse communication becomes necessary.",
                            ),
                            rule = rule,
                            yamlSnippet = formatRuleYaml(rule),
                        )
                    )
                }
            }
        }

        return adrs
    }

    private fun formatRuleYaml(rule: RuleDef): String {
        val sb = StringBuilder()
        sb.appendLine("  - name: ${rule.name}")
        if (!rule.description.isNullOrBlank()) {
            sb.appendLine("    description: \"${rule.description}\"")
        }
        sb.appendLine("    from:")
        if (rule.from.namePattern != null) {
            sb.appendLine("      namePattern: \"${rule.from.namePattern.replace("\\", "\\\\")}\"")
        }
        if (rule.from.annotations.isNotEmpty()) {
            sb.appendLine("      annotations: [${rule.from.annotations.joinToString(", ")}]")
        }
        sb.appendLine("    to:")
        if (rule.to.namePattern != null) {
            sb.appendLine("      namePattern: \"${rule.to.namePattern.replace("\\", "\\\\")}\"")
        }
        if (rule.to.annotations.isNotEmpty()) {
            sb.appendLine("      annotations: [${rule.to.annotations.joinToString(", ")}]")
        }
        if (!rule.message.isNullOrBlank()) {
            sb.appendLine("    message: \"${rule.message}\"")
        }
        sb.appendLine("    edgeKinds: [${rule.edgeKinds.joinToString(", ")}]")
        return sb.toString()
    }
}
