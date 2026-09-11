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

@Serializable
data class CalleeHit(
    val callee: String,
)

@Serializable
data class CappedCallees(
    val symbol: String,
    val items: List<CalleeHit>,
    val directCount: Int,
    val transitiveCount: Int,
    val totalCount: Int,
    val returnedCount: Int,
    val truncated: Boolean,
)

@Serializable
data class DependencyGraphNode(
    val id: String,
    val kind: String = "CLASS",
)

@Serializable
data class DependencyGraphEdge(
    val source: String,
    val target: String,
    val kind: String,
    val confidence: String,
)

@Serializable
data class DependencyGraphResult(
    val scope: String?,
    val nodes: List<DependencyGraphNode>,
    val edges: List<DependencyGraphEdge>,
    val totalNodes: Int,
    val totalEdges: Int,
)

class QueryEngineException(message: String) : RuntimeException(message)
