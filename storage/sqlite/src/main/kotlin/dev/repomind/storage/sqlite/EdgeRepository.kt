package dev.repomind.storage.sqlite

import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import java.sql.Connection

data class EdgeRow(
    val id: Long,
    val module: String,
    val sourceFqn: String,
    val targetFqn: String,
    val kind: String,
    val confidence: String,
    val line: Int = 0,
)

class EdgeRepository(private val connection: Connection) {

    fun init() {
        connection.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS edges (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    module TEXT NOT NULL,
                    source_fqn TEXT NOT NULL,
                    target_fqn TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    confidence TEXT NOT NULL,
                    line INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS symbol_references (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    module TEXT NOT NULL,
                    source_fqn TEXT NOT NULL,
                    target_fqn TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    confidence TEXT NOT NULL,
                    line INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_edges_source ON edges(source_fqn)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_edges_target ON edges(target_fqn)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_edges_module_kind ON edges(module, kind)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sym_refs_source ON symbol_references(source_fqn)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sym_refs_target ON symbol_references(target_fqn)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sym_refs_module_kind ON symbol_references(module, kind)")
        }
    }

    fun deleteModule(moduleName: String) {
        connection.prepareStatement("DELETE FROM edges WHERE module = ?").use { del ->
            del.setString(1, moduleName)
            del.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM symbol_references WHERE module = ?").use { del ->
            del.setString(1, moduleName)
            del.executeUpdate()
        }
    }

    fun replaceModule(moduleName: String, edges: List<DependencyEdge>): Int {
        var inserted = 0
        connection.autoCommit = false
        try {
            deleteModule(moduleName)
            val sql = "INSERT INTO edges (module, source_fqn, target_fqn, kind, confidence, line) VALUES (?, ?, ?, ?, ?, ?)"
            val refSql = "INSERT INTO symbol_references (module, source_fqn, target_fqn, kind, confidence, line) VALUES (?, ?, ?, ?, ?, ?)"
            connection.prepareStatement(sql).use { insert ->
                connection.prepareStatement(refSql).use { refInsert ->
                    for (edge in edges) {
                        insert.setString(1, moduleName)
                        insert.setString(2, edge.sourceFqn)
                        insert.setString(3, edge.targetFqn)
                        insert.setString(4, edge.kind.name)
                        insert.setString(5, edge.confidence.name)
                        insert.setInt(6, edge.line)
                        insert.addBatch()

                        refInsert.setString(1, moduleName)
                        refInsert.setString(2, edge.sourceFqn)
                        refInsert.setString(3, edge.targetFqn)
                        refInsert.setString(4, edge.kind.name)
                        refInsert.setString(5, edge.confidence.name)
                        refInsert.setInt(6, edge.line)
                        refInsert.addBatch()
                    }
                    insert.executeBatch()
                    refInsert.executeBatch()
                }
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

    fun findBySource(sourceFqn: String): List<EdgeRow> =
        query("SELECT $COLUMNS FROM edges WHERE source_fqn = ?", sourceFqn)

    fun findByTarget(targetFqn: String): List<EdgeRow> =
        query("SELECT $COLUMNS FROM edges WHERE target_fqn = ?", targetFqn)

    fun findByKind(kind: EdgeKind): List<EdgeRow> =
        query("SELECT $COLUMNS FROM edges WHERE kind = ?", kind.name)

    fun findAll(): List<EdgeRow> =
        query("SELECT $COLUMNS FROM edges")

    fun findRelatedTests(productionFqn: String): List<EdgeRow> =
        query(
            "SELECT $COLUMNS FROM edges WHERE target_fqn = ? AND kind = 'TESTS' ORDER BY confidence DESC",
            productionFqn,
        )

    fun findCallers(targetFqn: String): List<EdgeRow> =
        query(
            "SELECT $COLUMNS FROM edges WHERE (target_fqn = ? OR target_fqn LIKE ?) AND kind = 'CALLS' ORDER BY confidence DESC",
            targetFqn,
            "$targetFqn#%",
        )

    fun findCallees(sourceFqn: String): List<EdgeRow> =
        query(
            "SELECT $COLUMNS FROM edges WHERE (source_fqn = ? OR source_fqn LIKE ?) AND kind = 'CALLS' ORDER BY confidence DESC",
            sourceFqn,
            "$sourceFqn#%",
        )

    fun packageDependencies(): Map<String, Set<String>> {
        val map = mutableMapOf<String, MutableSet<String>>()
        val all = findAll()
        for (edge in all) {
            val srcOwner = edge.sourceFqn.substringBefore('#')
            val tgtOwner = edge.targetFqn.substringBefore('#')
            val srcPkg = srcOwner.substringBeforeLast('.').takeIf { '.' in srcOwner } ?: continue
            val tgtPkg = tgtOwner.substringBeforeLast('.').takeIf { '.' in tgtOwner } ?: continue
            if (srcPkg != tgtPkg) {
                map.getOrPut(srcPkg) { mutableSetOf() }.add(tgtPkg)
            }
        }
        return map
    }

    fun moduleDependencies(typeToModule: Map<String, String>): Map<String, Set<String>> {
        val map = mutableMapOf<String, MutableSet<String>>()
        val all = findAll()
        for (edge in all) {
            val srcMod = edge.module
            val tgtType = edge.targetFqn.substringBefore('#')
            val tgtMod = typeToModule[tgtType] ?: continue
            if (srcMod != tgtMod) {
                map.getOrPut(srcMod) { mutableSetOf() }.add(tgtMod)
            }
        }
        return map
    }

    fun count(): Long =
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM edges").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private val COLUMNS = "id, module, source_fqn, target_fqn, kind, confidence, line"

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
                            ),
                        )
                    }
                }
            }
        }
}
