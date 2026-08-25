package dev.repomind.core.query

import kotlinx.serialization.Serializable

@Serializable
data class CappedSymbols(
    val items: List<SymbolInfo>,
    val totalCount: Int,
    val returnedCount: Int,
    val truncated: Boolean,
)

@Serializable
data class SymbolInfo(
    val qualifiedName: String,
    val kind: String,
    val visibility: String,
    val filePath: String?,
    val lineStart: Int,
)

@Serializable
data class CallerHit(
    val caller: String,
)

@Serializable
data class CappedCallers(
    val symbol: String,
    val items: List<CallerHit>,
    val directCount: Int,
    val transitiveCount: Int,
    val totalCount: Int,
    val returnedCount: Int,
    val truncated: Boolean,
)

@Serializable
data class CappedTests(
    val symbol: String,
    val items: List<String>,
    val totalCount: Int,
    val returnedCount: Int,
    val truncated: Boolean,
)

class QueryEngineException(message: String) : RuntimeException(message)
