package dev.repomind.core.graph

import dev.repomind.core.model.RepoMindLimits
import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind

/**
 * Unified interface for graph operations, callers/callees lookups,
 * dependency traversals, and edge persistence.
 */
interface GraphStore : AutoCloseable {
    fun addEdges(module: String, edges: Collection<DependencyEdge>, filePath: String = "")
    fun replaceModule(moduleName: String, edges: List<DependencyEdge>): Int
    fun deleteModule(moduleName: String)
    fun findDirectCallers(targetFqn: String, minConfidence: Confidence = Confidence.POSSIBLE): List<DependencyEdge>
    fun findDirectCallees(sourceFqn: String, minConfidence: Confidence = Confidence.POSSIBLE): List<DependencyEdge>
    fun transitiveCallers(fqn: String, maxDepth: Int = Int.MAX_VALUE): Set<String>
    fun transitiveCallees(fqn: String, maxDepth: Int = RepoMindLimits.DEFAULT_GRAPH_DEPTH): Set<String>
    fun transitiveDependents(fqn: String): Set<String>
    fun hasDynamicDispatch(fqn: String): Boolean
    fun affectedTests(fqn: String): Set<String>
    fun findRelatedTests(productionFqn: String): List<DependencyEdge>
    fun packageDependencies(): Map<String, Set<String>>
    fun moduleDependencies(typeToModule: Map<String, String>): Map<String, Set<String>>
    fun allEdges(): List<DependencyEdge>
    fun count(): Long

    override fun close() {}
}
