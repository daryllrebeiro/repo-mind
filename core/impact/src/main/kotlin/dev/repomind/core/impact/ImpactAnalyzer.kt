package dev.repomind.core.impact

import dev.repomind.core.graph.InMemoryGraph
import dev.repomind.core.model.code.EdgeKind
import dev.repomind.core.model.code.TypeKind

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
) {

    fun analyze(symbolFqn: String, meta: SymbolMeta?): ImpactReport {
        val owner = symbolFqn.substringBefore('#')
        val directCallers = graph.adjacency.incoming[owner].orEmpty()
            .filter { it.kind == EdgeKind.CALLS }
            .map { it.sourceFqn.substringBefore('#') }
            .distinct()
        val transitiveCallers = graph.transitiveCallers(owner)
        val dependents = graph.transitiveDependents(owner)
        val affectedTests = graph.affectedTests(owner)
        val fanOut = graph.adjacency.outgoing[owner].orEmpty()
            .count { it.kind == EdgeKind.CALLS }

        val isPublicApi = meta?.let { it.visibility == "PUBLIC" || it.kind == TypeKind.INTERFACE.name } ?: false
        val isDatabaseRelated = meta?.annotations?.any { it in DB_ANNOTATIONS } ?: false

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
                detail = if (isDatabaseRelated) "annotations: ${meta?.annotations?.filter { it in DB_ANNOTATIONS }}" else "no persistence annotations",
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
        )
    }

    companion object {
        private val DB_ANNOTATIONS = setOf(
            "Entity", "Table", "Repository", "Document", "Mapper",
            "Transactional", "JdbcRepository", "CrudRepository", "JpaRepository",
        )
    }
}
