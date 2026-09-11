package dev.repomind.storage.sqlite

import dev.repomind.core.graph.GraphStore
import dev.repomind.core.model.RepoMindLimits
import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import java.sql.Connection

data class EdgeRow(
    val id: Long = 0,
    val module: String,
    val sourceFqn: String,
    val targetFqn: String,
    val kind: String,
    val confidence: String,
    val line: Int = 0,
    val callerMember: String = "",
    val filePath: String = "",
)

typealias EdgeRepository = SqliteGraphStore

/**
 * SQLite-backed implementation of [GraphStore].
 * Persists and queries edges using the consolidated `graph_edges` table with
 * automated migration from legacy `edges` and `symbol_references` tables.
 */
class SqliteGraphStore(private val connection: Connection) : GraphStore {

    fun init() {
        connection.createStatement().use { stmt ->
            // Enable foreign keys
            stmt.executeUpdate("PRAGMA foreign_keys = ON;")

            // Create target table graph_edges
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS graph_edges (
                    source_fqn TEXT NOT NULL,
                    target_fqn TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    confidence TEXT NOT NULL,
                    file_path TEXT NOT NULL DEFAULT '',
                    module TEXT NOT NULL DEFAULT '',
                    line INTEGER NOT NULL DEFAULT 0,
                    caller_member TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY (source_fqn, target_fqn, kind, caller_member, line, module)
                )
                """.trimIndent(),
            )
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_edges_target ON graph_edges(target_fqn, confidence)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_edges_source ON graph_edges(source_fqn, confidence)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_edges_file ON graph_edges(file_path)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_edges_module ON graph_edges(module)")

            // Check if legacy 'edges' table exists
            val rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='edges'")
            val legacyEdgesExists = rs.next()
            rs.close()

            if (legacyEdgesExists) {
                stmt.executeUpdate(
                    """
                    INSERT OR IGNORE INTO graph_edges (source_fqn, target_fqn, kind, confidence, module, line, caller_member, file_path)
                    SELECT source_fqn, target_fqn, kind, confidence, module, line, '', '' FROM edges
                    """.trimIndent(),
                )
                stmt.executeUpdate("DROP TABLE IF EXISTS symbol_references")
                stmt.executeUpdate("DROP TABLE IF EXISTS edges")
            }
        }
    }

    override fun addEdges(module: String, edges: Collection<DependencyEdge>, filePath: String) {
        val sql = "INSERT OR REPLACE INTO graph_edges (source_fqn, target_fqn, kind, confidence, file_path, module, line, caller_member) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        connection.prepareStatement(sql).use { ps ->
            for (edge in edges) {
                ps.setString(1, edge.sourceFqn)
                ps.setString(2, edge.targetFqn)
                ps.setString(3, edge.kind.name)
                ps.setString(4, edge.confidence.name)
                ps.setString(5, filePath)
                ps.setString(6, module)
                ps.setInt(7, edge.line)
                ps.setString(8, edge.callerMember.orEmpty())
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    override fun replaceModule(moduleName: String, edges: List<DependencyEdge>): Int {
        var inserted = 0
        connection.autoCommit = false
        try {
            deleteModule(moduleName)
            val sql = "INSERT OR REPLACE INTO graph_edges (source_fqn, target_fqn, kind, confidence, file_path, module, line, caller_member) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            connection.prepareStatement(sql).use { insert ->
                for (edge in edges) {
                    insert.setString(1, edge.sourceFqn)
                    insert.setString(2, edge.targetFqn)
                    insert.setString(3, edge.kind.name)
                    insert.setString(4, edge.confidence.name)
                    insert.setString(5, "")
                    insert.setString(6, moduleName)
                    insert.setInt(7, edge.line)
                    insert.setString(8, edge.callerMember.orEmpty())
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            connection.commit()
            inserted = edges.size
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
        return inserted
    }

    override fun deleteModule(moduleName: String) {
        connection.prepareStatement("DELETE FROM graph_edges WHERE module = ?").use { del ->
            del.setString(1, moduleName)
            del.executeUpdate()
        }
    }

    override fun findDirectCallers(targetFqn: String, minConfidence: Confidence): List<DependencyEdge> {
        val owner = targetFqn.substringBefore('#')
        val sql = if (minConfidence == Confidence.CONFIRMED) {
            "SELECT source_fqn, target_fqn, kind, confidence, line, caller_member FROM graph_edges WHERE (target_fqn = ? OR target_fqn = ? OR target_fqn LIKE ?) AND kind = 'CALLS' AND confidence = 'CONFIRMED' ORDER BY line"
        } else {
            "SELECT source_fqn, target_fqn, kind, confidence, line, caller_member FROM graph_edges WHERE (target_fqn = ? OR target_fqn = ? OR target_fqn LIKE ?) AND kind = 'CALLS' ORDER BY confidence DESC, line"
        }
        return connection.prepareStatement(sql).use { ps ->
            ps.setString(1, targetFqn)
            ps.setString(2, owner)
            ps.setString(3, "$owner#%")
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            DependencyEdge(
                                sourceFqn = rs.getString("source_fqn"),
                                targetFqn = rs.getString("target_fqn"),
                                kind = EdgeKind.valueOf(rs.getString("kind")),
                                confidence = Confidence.valueOf(rs.getString("confidence")),
                                line = rs.getInt("line"),
                                callerMember = rs.getString("caller_member").takeIf { it.isNotEmpty() },
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun findDirectCallees(sourceFqn: String, minConfidence: Confidence): List<DependencyEdge> {
        val owner = sourceFqn.substringBefore('#')
        val sql = if (minConfidence == Confidence.CONFIRMED) {
            "SELECT source_fqn, target_fqn, kind, confidence, line, caller_member FROM graph_edges WHERE (source_fqn = ? OR source_fqn = ? OR source_fqn LIKE ?) AND kind = 'CALLS' AND confidence = 'CONFIRMED' ORDER BY line"
        } else {
            "SELECT source_fqn, target_fqn, kind, confidence, line, caller_member FROM graph_edges WHERE (source_fqn = ? OR source_fqn = ? OR source_fqn LIKE ?) AND kind = 'CALLS' ORDER BY confidence DESC, line"
        }
        return connection.prepareStatement(sql).use { ps ->
            ps.setString(1, sourceFqn)
            ps.setString(2, owner)
            ps.setString(3, "$owner#%")
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            DependencyEdge(
                                sourceFqn = rs.getString("source_fqn"),
                                targetFqn = rs.getString("target_fqn"),
                                kind = EdgeKind.valueOf(rs.getString("kind")),
                                confidence = Confidence.valueOf(rs.getString("confidence")),
                                line = rs.getInt("line"),
                                callerMember = rs.getString("caller_member").takeIf { it.isNotEmpty() },
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun transitiveCallers(fqn: String, maxDepth: Int): Set<String> {
        if (maxDepth <= 0) return emptySet()
        val owner = fqn.substringBefore('#')
        val sql = """
            WITH RECURSIVE callers_cte(caller_fqn, depth) AS (
                SELECT DISTINCT
                    CASE WHEN instr(source_fqn, '#') > 0 THEN substr(source_fqn, 1, instr(source_fqn, '#') - 1) ELSE source_fqn END,
                    1
                FROM graph_edges
                WHERE (target_fqn = ? OR target_fqn = ? OR target_fqn LIKE ?)
                  AND kind = 'CALLS'
                UNION
                SELECT DISTINCT
                    CASE WHEN instr(e.source_fqn, '#') > 0 THEN substr(e.source_fqn, 1, instr(e.source_fqn, '#') - 1) ELSE e.source_fqn END,
                    c.depth + 1
                FROM graph_edges e
                JOIN callers_cte c ON (e.target_fqn = c.caller_fqn OR e.target_fqn LIKE c.caller_fqn || '#%')
                WHERE e.kind = 'CALLS'
                  AND c.depth < ?
            )
            SELECT DISTINCT caller_fqn FROM callers_cte WHERE caller_fqn != ? LIMIT ?
        """.trimIndent()
        return connection.prepareStatement(sql).use { ps ->
            ps.setString(1, fqn)
            ps.setString(2, owner)
            ps.setString(3, "$owner#%")
            ps.setInt(4, maxDepth)
            ps.setString(5, owner)
            ps.setInt(6, RepoMindLimits.MAX_GRAPH_NODES)
            ps.executeQuery().use { rs ->
                buildSet {
                    while (rs.next()) {
                        add(rs.getString(1))
                    }
                }
            }
        }
    }

    override fun transitiveCallees(fqn: String, maxDepth: Int): Set<String> {
        if (maxDepth <= 0) return emptySet()
        val owner = fqn.substringBefore('#')
        val sql = """
            WITH RECURSIVE callees_cte(callee_fqn, depth) AS (
                SELECT DISTINCT
                    CASE WHEN instr(target_fqn, '#') > 0 THEN substr(target_fqn, 1, instr(target_fqn, '#') - 1) ELSE target_fqn END,
                    1
                FROM graph_edges
                WHERE (source_fqn = ? OR source_fqn = ? OR source_fqn LIKE ?)
                  AND kind = 'CALLS'
                UNION
                SELECT DISTINCT
                    CASE WHEN instr(e.target_fqn, '#') > 0 THEN substr(e.target_fqn, 1, instr(e.target_fqn, '#') - 1) ELSE e.target_fqn END,
                    c.depth + 1
                FROM graph_edges e
                JOIN callees_cte c ON (e.source_fqn = c.callee_fqn OR e.source_fqn LIKE c.callee_fqn || '#%')
                WHERE e.kind = 'CALLS'
                  AND c.depth < ?
            )
            SELECT DISTINCT callee_fqn FROM callees_cte WHERE callee_fqn != ? LIMIT ?
        """.trimIndent()
        return connection.prepareStatement(sql).use { ps ->
            ps.setString(1, fqn)
            ps.setString(2, owner)
            ps.setString(3, "$owner#%")
            ps.setInt(4, maxDepth)
            ps.setString(5, owner)
            ps.setInt(6, RepoMindLimits.MAX_GRAPH_NODES)
            ps.executeQuery().use { rs ->
                buildSet {
                    while (rs.next()) {
                        add(rs.getString(1))
                    }
                }
            }
        }
    }

    override fun transitiveDependents(fqn: String): Set<String> {
        val owner = fqn.substringBefore('#')
        val sql = """
            WITH RECURSIVE dependents_cte(dep_fqn, depth) AS (
                SELECT DISTINCT
                    CASE WHEN instr(source_fqn, '#') > 0 THEN substr(source_fqn, 1, instr(source_fqn, '#') - 1) ELSE source_fqn END,
                    1
                FROM graph_edges
                WHERE (target_fqn = ? OR target_fqn = ? OR target_fqn LIKE ?)
                  AND kind IN ('CALLS', 'USES', 'EXTENDS', 'IMPLEMENTS')
                UNION
                SELECT DISTINCT
                    CASE WHEN instr(e.source_fqn, '#') > 0 THEN substr(e.source_fqn, 1, instr(e.source_fqn, '#') - 1) ELSE e.source_fqn END,
                    d.depth + 1
                FROM graph_edges e
                JOIN dependents_cte d ON (e.target_fqn = d.dep_fqn OR e.target_fqn LIKE d.dep_fqn || '#%')
                WHERE e.kind IN ('CALLS', 'USES', 'EXTENDS', 'IMPLEMENTS')
                  AND d.depth < ?
            )
            SELECT DISTINCT dep_fqn FROM dependents_cte WHERE dep_fqn != ? LIMIT ?
        """.trimIndent()
        return connection.prepareStatement(sql).use { ps ->
            ps.setString(1, fqn)
            ps.setString(2, owner)
            ps.setString(3, "$owner#%")
            ps.setInt(4, RepoMindLimits.DEFAULT_GRAPH_DEPTH)
            ps.setString(5, owner)
            ps.setInt(6, RepoMindLimits.MAX_GRAPH_NODES)
            ps.executeQuery().use { rs ->
                buildSet {
                    while (rs.next()) {
                        add(rs.getString(1))
                    }
                }
            }
        }
    }

    override fun hasDynamicDispatch(fqn: String): Boolean {
        val owner = fqn.substringBefore('#')
        val sql = "SELECT target_fqn FROM graph_edges WHERE (source_fqn = ? OR source_fqn = ? OR source_fqn LIKE ?) AND kind = 'CALLS'"
        return connection.prepareStatement(sql).use { ps ->
            ps.setString(1, fqn)
            ps.setString(2, owner)
            ps.setString(3, "$owner#%")
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val tgt = rs.getString(1)
                    if ("reflect" in tgt || tgt.startsWith("java.lang.reflect")) return true
                }
                false
            }
        }
    }

    override fun affectedTests(fqn: String): Set<String> {
        val owner = fqn.substringBefore('#')
        val sql = """
            WITH RECURSIVE dependents_cte(dep_fqn, depth) AS (
                SELECT DISTINCT
                    CASE WHEN instr(source_fqn, '#') > 0 THEN substr(source_fqn, 1, instr(source_fqn, '#') - 1) ELSE source_fqn END,
                    1
                FROM graph_edges
                WHERE (target_fqn = ? OR target_fqn = ? OR target_fqn LIKE ?)
                  AND kind IN ('CALLS', 'USES', 'EXTENDS', 'IMPLEMENTS')
                UNION
                SELECT DISTINCT
                    CASE WHEN instr(e.source_fqn, '#') > 0 THEN substr(e.source_fqn, 1, instr(e.source_fqn, '#') - 1) ELSE e.source_fqn END,
                    d.depth + 1
                FROM graph_edges e
                JOIN dependents_cte d ON (e.target_fqn = d.dep_fqn OR e.target_fqn LIKE d.dep_fqn || '#%')
                WHERE e.kind IN ('CALLS', 'USES', 'EXTENDS', 'IMPLEMENTS')
                  AND d.depth < ?
            )
            SELECT DISTINCT source_fqn 
            FROM graph_edges 
            WHERE kind = 'TESTS' 
              AND (target_fqn = ? OR target_fqn IN (SELECT dep_fqn FROM dependents_cte))
            LIMIT ?
        """.trimIndent()
        return connection.prepareStatement(sql).use { ps ->
            ps.setString(1, fqn)
            ps.setString(2, owner)
            ps.setString(3, "$owner#%")
            ps.setInt(4, RepoMindLimits.DEFAULT_GRAPH_DEPTH)
            ps.setString(5, owner)
            ps.setInt(6, RepoMindLimits.MAX_GRAPH_NODES)
            ps.executeQuery().use { rs ->
                buildSet {
                    while (rs.next()) {
                        add(rs.getString(1))
                    }
                }
            }
        }
    }

    override fun findRelatedTests(productionFqn: String): List<DependencyEdge> {
        val owner = productionFqn.substringBefore('#')
        val sql = "SELECT source_fqn, target_fqn, kind, confidence, line, caller_member FROM graph_edges WHERE target_fqn = ? AND kind = 'TESTS' ORDER BY confidence DESC"
        return connection.prepareStatement(sql).use { ps ->
            ps.setString(1, owner)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            DependencyEdge(
                                sourceFqn = rs.getString("source_fqn"),
                                targetFqn = rs.getString("target_fqn"),
                                kind = EdgeKind.valueOf(rs.getString("kind")),
                                confidence = Confidence.valueOf(rs.getString("confidence")),
                                line = rs.getInt("line"),
                                callerMember = rs.getString("caller_member").takeIf { it.isNotEmpty() },
                            ),
                        )
                    }
                }
            }
        }
    }

    fun findBySource(sourceFqn: String): List<EdgeRow> =
        query("SELECT $COLUMNS FROM graph_edges WHERE source_fqn = ?", sourceFqn)

    fun findByTarget(targetFqn: String): List<EdgeRow> =
        query("SELECT $COLUMNS FROM graph_edges WHERE target_fqn = ?", targetFqn)

    fun findByKind(kind: EdgeKind): List<EdgeRow> =
        query("SELECT $COLUMNS FROM graph_edges WHERE kind = ?", kind.name)

    fun findAll(): List<EdgeRow> =
        query("SELECT $COLUMNS FROM graph_edges")

    fun findCallers(targetFqn: String): List<EdgeRow> =
        query(
            "SELECT $COLUMNS FROM graph_edges WHERE (target_fqn = ? OR target_fqn LIKE ?) AND kind = 'CALLS' ORDER BY confidence DESC",
            targetFqn,
            "$targetFqn#%",
        )

    fun findCallees(sourceFqn: String): List<EdgeRow> =
        query(
            "SELECT $COLUMNS FROM graph_edges WHERE (source_fqn = ? OR source_fqn LIKE ?) AND kind = 'CALLS' ORDER BY confidence DESC",
            sourceFqn,
            "$sourceFqn#%",
        )

    override fun packageDependencies(): Map<String, Set<String>> {
        val map = mutableMapOf<String, MutableSet<String>>()
        val sql = "SELECT source_fqn, target_fqn FROM graph_edges"
        connection.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    val srcOwner = rs.getString(1).substringBefore('#')
                    val tgtOwner = rs.getString(2).substringBefore('#')
                    val srcPkg = srcOwner.substringBeforeLast('.').takeIf { '.' in srcOwner } ?: continue
                    val tgtPkg = tgtOwner.substringBeforeLast('.').takeIf { '.' in tgtOwner } ?: continue
                    if (srcPkg != tgtPkg) {
                        map.getOrPut(srcPkg) { mutableSetOf() }.add(tgtPkg)
                    }
                }
            }
        }
        return map
    }

    override fun moduleDependencies(typeToModule: Map<String, String>): Map<String, Set<String>> {
        val map = mutableMapOf<String, MutableSet<String>>()
        val sql = "SELECT module, target_fqn FROM graph_edges"
        connection.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    val srcMod = rs.getString(1)
                    val tgtType = rs.getString(2).substringBefore('#')
                    val tgtMod = typeToModule[tgtType] ?: continue
                    if (srcMod != tgtMod) {
                        map.getOrPut(srcMod) { mutableSetOf() }.add(tgtMod)
                    }
                }
            }
        }
        return map
    }

    override fun allEdges(): List<DependencyEdge> {
        val sql = "SELECT source_fqn, target_fqn, kind, confidence, line, caller_member FROM graph_edges"
        return connection.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            DependencyEdge(
                                sourceFqn = rs.getString("source_fqn"),
                                targetFqn = rs.getString("target_fqn"),
                                kind = EdgeKind.valueOf(rs.getString("kind")),
                                confidence = Confidence.valueOf(rs.getString("confidence")),
                                line = rs.getInt("line"),
                                callerMember = rs.getString("caller_member").takeIf { it.isNotEmpty() },
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun count(): Long =
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM graph_edges").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private val COLUMNS = "rowid as id, module, source_fqn, target_fqn, kind, confidence, line, caller_member, file_path"

    private fun query(sql: String, vararg args: Any): List<EdgeRow> =
        connection.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, arg -> ps.setObject(i + 1, arg) }
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            EdgeRow(
                                id = rs.getLong("id"),
                                module = rs.getString("module"),
                                sourceFqn = rs.getString("source_fqn"),
                                targetFqn = rs.getString("target_fqn"),
                                kind = rs.getString("kind"),
                                confidence = rs.getString("confidence"),
                                line = rs.getInt("line"),
                                callerMember = rs.getString("caller_member"),
                                filePath = rs.getString("file_path"),
                            ),
                        )
                    }
                }
            }
        }
}
