package dev.repomind.core.impact

import dev.repomind.core.graph.InMemoryGraph
import dev.repomind.core.model.code.EdgeKind
import dev.repomind.core.model.code.TypeKind

import dev.repomind.core.model.code.Confidence

data class SymbolMeta(
    val kind: String,
    val visibility: String,
    val annotations: List<String> = emptyList(),
)

class ImpactAnalyzer(
    private val graph: InMemoryGraph,
    private val weights: ImpactWeights = ImpactWeights(),
    private val topCallersLimit: Int = 50,
    private val violatingSymbols: Set<String> = emptySet(),
    private val configBindings: List<ConfigWiringInfo> = emptyList(),
    private val typeToModule: Map<String, String> = emptyMap(),
) {

    fun analyze(symbolFqn: String, meta: SymbolMeta?): ImpactReport {
        val owner = symbolFqn.substringBefore('#')
        val incomingEdges = graph.adjacency.incoming[owner].orEmpty().filter { it.kind == EdgeKind.CALLS } +
            (if ('#' in symbolFqn) graph.adjacency.incoming[symbolFqn].orEmpty().filter { it.kind == EdgeKind.CALLS } else emptyList())

        val directCallers = incomingEdges
            .map { if (it.callerMember != null) "${it.sourceFqn}#${it.callerMember}" else it.sourceFqn }
            .distinct()

        val certainCallers = incomingEdges
            .filter { it.confidence == Confidence.CONFIRMED && !it.targetFqn.startsWith("java.lang.reflect") }
            .map { if (it.callerMember != null) "${it.sourceFqn}#${it.callerMember}" else it.sourceFqn }
            .distinct()
            .sorted()

        val possibleCallers = incomingEdges
            .filter { it.confidence == Confidence.POSSIBLE || it.targetFqn.startsWith("java.lang.reflect") }
            .map { if (it.callerMember != null) "${it.sourceFqn}#${it.callerMember}" else it.sourceFqn }
            .distinct()
            .sorted()

        val transitiveCallers = graph.transitiveCallers(owner)
        val dependents = graph.transitiveDependents(owner)
        val affectedTests = graph.affectedTests(owner).sorted()
        val fanOut = graph.adjacency.outgoing[owner].orEmpty()
            .count { it.kind == EdgeKind.CALLS }

        val isPublicApi = meta?.let { it.visibility == "PUBLIC" || it.kind == TypeKind.INTERFACE.name } ?: false
        val isDatabaseRelated = meta?.annotations?.any { it in DB_ANNOTATIONS } ?: false
        val hasDynamicDispatch = graph.hasDynamicDispatch(owner) ||
            incomingEdges.any { it.targetFqn.startsWith("java.lang.reflect") }

        val signals = listOf(
            ImpactSignal(
                name = "public-api",
                weight = weights.publicApi,
                applied = isPublicApi,
                detail = if (isPublicApi) "symbol is public API" else "symbol is not exported API",
            ),
            ImpactSignal(
                name = "many-callers",
                weight = weights.manyCallers,
                applied = directCallers.size >= weights.manyCallersThreshold,
                detail = "${directCallers.size} direct callers (threshold ${weights.manyCallersThreshold})",
            ),
            ImpactSignal(
                name = "database-interaction",
                weight = weights.databaseInteraction,
                applied = isDatabaseRelated,
                detail = if (isDatabaseRelated) "annotations: ${meta.annotations.filter { it in DB_ANNOTATIONS }}" else "no persistence annotations",
            ),
            ImpactSignal(
                name = "high-fan-out",
                weight = weights.highFanOut,
                applied = fanOut >= weights.highFanOutThreshold,
                detail = "$fanOut outgoing calls (threshold ${weights.highFanOutThreshold})",
            ),
            ImpactSignal(
                name = "few-tests",
                weight = weights.fewTests,
                applied = affectedTests.isEmpty(),
                detail = if (affectedTests.isEmpty()) "no tests reference this code or its dependents" else "${affectedTests.size} tests may be affected",
            ),
            ImpactSignal(
                name = "architecture-violation",
                weight = weights.architectureViolation,
                applied = owner in violatingSymbols,
                detail = if (owner in violatingSymbols) "symbol violates an architecture rule" else "no architecture rule violations",
            ),
        )

        val score = signals.filter { it.applied }.sumOf { it.weight }.coerceAtMost(100)
        val level = ImpactLevel.forScore(score)

        val affectedConfig = configBindings
            .filter { it.targetFqn.startsWith(owner) || it.propertyKey == owner }
            .map { "${it.kind}: ${it.targetFqn} -> ${it.propertyKey}" }

        val allAffectedTypes = (directCallers.map { it.substringBefore('#') } + transitiveCallers + owner).toSet()
        val affectedModules = allAffectedTypes.mapNotNull { typeToModule[it] }.distinct()

        val blastRadius = BlastRadius(
            affectedMethods = directCallers.count { '#' in it },
            affectedClasses = allAffectedTypes.size,
            affectedTests = affectedTests.size,
            affectedModules = affectedModules.size.coerceAtLeast(if (allAffectedTypes.isNotEmpty()) 1 else 0),
        )

        return ImpactReport(
            symbol = symbolFqn,
            score = score,
            level = level.name,
            signals = signals,
            directCallerCount = directCallers.size,
            transitiveCallerCount = transitiveCallers.size,
            dependentCount = dependents.size,
            affectedTestCount = affectedTests.size,
            topCallers = directCallers.sorted().take(topCallersLimit),
            truncatedCallers = directCallers.size > topCallersLimit,
            certainCallers = certainCallers,
            possibleCallers = possibleCallers,
            affectedTests = affectedTests,
            affectedConfigWiring = affectedConfig,
            hasDynamicDispatch = hasDynamicDispatch,
            blastRadius = blastRadius,
        )
    }

    fun analyzeDiff(
        changedSymbols: Collection<String>,
        metaProvider: (String) -> SymbolMeta? = { null },
    ): DiffImpactReport {
        val reports = changedSymbols.associateWith { analyze(it, metaProvider(it)) }
        val maxScore = reports.values.maxOfOrNull { it.score } ?: 0
        val highestLevel = ImpactLevel.forScore(maxScore).name

        val allCertain = reports.values.flatMap { it.certainCallers }.distinct().sorted()
        val allPossible = reports.values.flatMap { it.possibleCallers }.distinct().sorted()
        val allTests = reports.values.flatMap { it.affectedTests }.distinct().sorted()
        val allMethods = (allCertain + allPossible).filter { '#' in it }.distinct().size
        val allClasses = (reports.values.flatMap { r -> r.certainCallers + r.possibleCallers }
            .map { it.substringBefore('#') } + changedSymbols.map { it.substringBefore('#') }).distinct()
        val allModules = allClasses.mapNotNull { typeToModule[it] }.distinct().size.coerceAtLeast(if (allClasses.isNotEmpty()) 1 else 0)

        val blast = BlastRadius(
            affectedMethods = allMethods,
            affectedClasses = allClasses.size,
            affectedTests = allTests.size,
            affectedModules = allModules,
        )

        return DiffImpactReport(
            changedSymbols = changedSymbols.toList(),
            maxScore = maxScore,
            highestLevel = highestLevel,
            blastRadius = blast,
            allCertainCallers = allCertain,
            allPossibleCallers = allPossible,
            allAffectedTests = allTests,
            perSymbolReports = reports,
        )
    }

    companion object {
        private val DB_ANNOTATIONS = setOf(
            "Entity", "Table", "Repository", "Document", "Mapper",
            "Transactional", "JdbcRepository", "CrudRepository", "JpaRepository",
        )
    }
}
