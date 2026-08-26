package dev.repomind.core.report

import kotlinx.serialization.Serializable

@Serializable
data class Finding(
    val detector: String,
    val severity: String,
    val summary: String,
    val evidence: List<String>,
)

@Serializable
data class Flow(
    val name: String,
    val entryPoint: String,
    val participants: List<String>,
    val dashedFromIndex: Int,
)
