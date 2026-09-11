package dev.repomind.core.impact

import kotlinx.serialization.Serializable

enum class ImpactLevel(val minScore: Int) {
    LOW(0), MEDIUM(21), HIGH(51), CRITICAL(76);

    companion object {
        fun forScore(score: Int): ImpactLevel = entries.last { score >= it.minScore }
    }
}

@Serializable
data class ImpactSignal(
    val name: String,
    val weight: Int,
    val applied: Boolean,
    val detail: String,
)

data class ImpactWeights(
    val publicApi: Int = 30,
    val manyCallers: Int = 20,
    val databaseInteraction: Int = 20,
    val highFanOut: Int = 15,
    val fewTests: Int = 10,
    val architectureViolation: Int = 10,
    val manyCallersThreshold: Int = 3,
    val highFanOutThreshold: Int = 5,
)

@Serializable
data class BlastRadius(
    val affectedMethods: Int,
    val affectedClasses: Int,
    val affectedTests: Int,
    val affectedModules: Int,
)

@Serializable
data class ConfigWiringInfo(
    val kind: String,
    val targetFqn: String,
    val propertyKey: String,
)

@Serializable
data class ImpactReport(
    val symbol: String,
    val score: Int,
    val level: String,
    val signals: List<ImpactSignal>,
    val directCallerCount: Int,
    val transitiveCallerCount: Int,
    val dependentCount: Int,
    val affectedTestCount: Int,
    val topCallers: List<String>,
    val truncatedCallers: Boolean,
    val certainCallers: List<String> = emptyList(),
    val possibleCallers: List<String> = emptyList(),
    val affectedTests: List<String> = emptyList(),
    val affectedConfigWiring: List<String> = emptyList(),
    val hasDynamicDispatch: Boolean = false,
    val blastRadius: BlastRadius = BlastRadius(0, 0, 0, 0),
)

@Serializable
data class DiffImpactReport(
    val changedSymbols: List<String>,
    val maxScore: Int,
    val highestLevel: String,
    val blastRadius: BlastRadius,
    val allCertainCallers: List<String>,
    val allPossibleCallers: List<String>,
    val allAffectedTests: List<String>,
    val perSymbolReports: Map<String, ImpactReport>,
)
