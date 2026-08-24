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
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_edges_source ON edges(source_fqn)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_edges_target ON edges(target_fqn)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_edges_module_kind ON edges(module, kind)")
        }
    }

    fun replaceModule(moduleName: String, edges: List<DependencyEdge>): Int {
        var inserted = 0
        connection.autoCommit = false
        try {
            connection.prepareStatement("DELETE FROM edges WHERE module = ?").use { del ->
                del.setString(1, moduleName)
                del.executeUpdate()
            }
            val sql = "INSERT INTO edges (module, source_fqn, target_fqn, kind, confidence, line) VALUES (?, ?, ?, ?, ?, ?)"
            connection.prepareStatement(sql).use { insert ->
                for (edge in edges) {
                    insert.setString(1, moduleName)
                    insert.setString(2, edge.sourceFqn)
                    insert.setString(3, edge.targetFqn)
                    insert.setString(4, edge.kind.name)
                    insert.setString(5, edge.confidence.name)
                    insert.setInt(6, edge.line)
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

    fun count(): Long =
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM edges").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private val COLUMNS = "id, module, source_fqn, target_fqn, kind, confidence"

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
                            ),
                        )
                    }
                }
            }
        }
}
