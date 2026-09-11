package dev.repomind.core.query

import dev.repomind.core.graph.GraphStore
import dev.repomind.core.impact.ImpactAnalyzer
import dev.repomind.core.impact.ImpactReport
import dev.repomind.core.impact.SymbolMeta
import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import dev.repomind.storage.sqlite.SymbolDatabase
import java.nio.file.Files
import java.nio.file.Path

class RepoQueryEngine(
    private val dbPath: Path,
    private val defaultLimit: Int = 20,
    readOnly: Boolean = true,
) : AutoCloseable {

    private val db: SymbolDatabase
    private val graph: GraphStore

    init {
        if (!Files.isRegularFile(dbPath)) {
            throw QueryEngineException("no index at $dbPath — run 'repomind index <repo>' first")
        }
        db = SymbolDatabase.open(dbPath, readOnly = readOnly)
        graph = db.graphStore
    }

    fun findSymbol(prefix: String, limit: Int = defaultLimit): CappedSymbols {
        val rows = db.findByNamePrefix(prefix, limit = limit + 1)
        val truncated = rows.size > limit
        val items = rows.take(limit).map { r ->
            SymbolInfo(r.qualifiedName, r.kind, r.visibility, r.filePath, r.lineStart)
        }
        return CappedSymbols(
            items = items,
            totalCount = if (truncated) countMatching(prefix).toInt() else items.size,
            returnedCount = items.size,
            truncated = truncated,
        )
    }

    private fun countMatching(prefix: String): Long =
        db.countByNamePrefix(prefix)

    fun findCallers(symbol: String, limit: Int = defaultLimit): CappedCallers {
        val owner = symbol.substringBefore('#')
        val direct = graph.findDirectCallers(owner)
            .map { it.sourceFqn.substringBefore('#') }
            .distinct()
        val transitive = graph.transitiveCallers(owner)
        val ordered = (direct + (transitive - direct.toSet())).sorted()
        val truncated = ordered.size > limit
        return CappedCallers(
            symbol = symbol,
            items = ordered.take(limit).map { CallerHit(it) },
            directCount = direct.size,
            transitiveCount = transitive.size,
            totalCount = ordered.size,
            returnedCount = minOf(ordered.size, limit),
            truncated = truncated,
        )
    }

    fun findRelatedTests(symbol: String, limit: Int = defaultLimit): CappedTests {
        val distinctOrdered = graph.findRelatedTests(symbol)
            .map { it.sourceFqn }
            .distinct()
            .sorted()
        val truncated = distinctOrdered.size > limit
        return CappedTests(
            symbol = symbol,
            items = distinctOrdered.take(limit),
            totalCount = distinctOrdered.size,
            returnedCount = minOf(distinctOrdered.size, limit),
            truncated = truncated,
        )
    }

    fun findCallees(symbol: String, limit: Int = defaultLimit): CappedCallees {
        val owner = symbol.substringBefore('#')
        val direct = graph.findDirectCallees(owner)
            .map { it.targetFqn.substringBefore('#') }
            .distinct()
        val transitive = graph.transitiveCallees(owner)
        val ordered = (direct + (transitive - direct.toSet())).sorted()
        val truncated = ordered.size > limit
        return CappedCallees(
            symbol = symbol,
            items = ordered.take(limit).map { CalleeHit(it) },
            directCount = direct.size,
            transitiveCount = transitive.size,
            totalCount = ordered.size,
            returnedCount = minOf(ordered.size, limit),
            truncated = truncated,
        )
    }

    fun checkArchitectureRules(): dev.repomind.core.rules.RulesReport {
        val rulesPath = dbPath.resolveSibling("rules.yaml")
        val altRulesPath = dbPath.parent?.parent?.resolve(".repomind/rules.yaml")
        val finalPath = when {
            Files.isRegularFile(rulesPath) -> rulesPath
            altRulesPath != null && Files.isRegularFile(altRulesPath) -> altRulesPath
            else -> null
        }
        if (finalPath == null) {
            return dev.repomind.core.rules.RulesReport(evaluatedRules = 0, violations = emptyList(), checkedTypes = 0)
        }
        val rules = dev.repomind.core.rules.RuleLoader.load(finalPath)
        val types = db.allTypes().map { row ->
            dev.repomind.core.rules.TypeStereotypeInfo(row.qualifiedName, row.annotations)
        }
        val edges = db.edges.findAll().map { row ->
            DependencyEdge(row.sourceFqn, row.targetFqn, EdgeKind.valueOf(row.kind), Confidence.valueOf(row.confidence), row.line)
        }
        return dev.repomind.core.rules.RuleEvaluator().evaluate(rules, types, edges)
    }

    fun dependencyGraph(scope: String? = null, limit: Int = defaultLimit, cursor: String? = null): DependencyGraphResult {
        val allEdges = db.edges.findAll()
        val matchingEdges = if (scope.isNullOrBlank()) {
            allEdges
        } else {
            allEdges.filter { it.sourceFqn.startsWith(scope) || it.targetFqn.startsWith(scope) }
        }
        val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val pagedEdges = matchingEdges.drop(offset).take(limit)
        val nextCursor = if (offset + limit < matchingEdges.size) (offset + limit).toString() else null
        val nodeIds = (pagedEdges.map { it.sourceFqn.substringBefore('#') } + pagedEdges.map { it.targetFqn.substringBefore('#') }).distinct().sorted()
        return DependencyGraphResult(
            scope = scope,
            nodes = nodeIds.map { DependencyGraphNode(it) },
            edges = pagedEdges.map { DependencyGraphEdge(it.sourceFqn, it.targetFqn, it.kind, it.confidence) },
            totalNodes = nodeIds.size,
            totalEdges = matchingEdges.size,
            nextCursor = nextCursor,
        )
    }

    fun impact(symbol: String): ImpactReport {
        val metaRow = db.findByFqn(symbol).firstOrNull() ?: db.findByFqn(symbol.substringBefore('#')).firstOrNull()
        val meta = metaRow?.let { SymbolMeta(kind = it.kind, visibility = it.visibility, annotations = it.annotations) }
        return ImpactAnalyzer(graph, violatingSymbols = architectureViolators()).analyze(symbol, meta)
    }

    private fun architectureViolators(): Set<String> {
        violatorsCache?.let { return it }
        val rulesPath = dbPath.resolveSibling("rules.yaml")
        if (!Files.isRegularFile(rulesPath)) {
            violatorsCache = emptySet()
            return emptySet()
        }
        val rules = dev.repomind.core.rules.RuleLoader.load(rulesPath)
        val types = db.allTypes().map { row ->
            dev.repomind.core.rules.TypeStereotypeInfo(row.qualifiedName, row.annotations)
        }
        val report = dev.repomind.core.rules.RuleEvaluator().evaluate(
            rules,
            types,
            db.edges.findAll().map { row ->
                DependencyEdge(row.sourceFqn, row.targetFqn, EdgeKind.valueOf(row.kind), Confidence.valueOf(row.confidence))
            },
        )
        val computed = report.violations.map { it.sourceFqn.substringBefore('#') }.toSet()
        violatorsCache = computed
        return computed
    }

    private var violatorsCache: Set<String>? = null

    override fun close() {
        db.close()
    }
}
