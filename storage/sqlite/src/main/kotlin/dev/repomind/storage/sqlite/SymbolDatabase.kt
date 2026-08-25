package dev.repomind.storage.sqlite

import dev.repomind.core.model.Visibility
import dev.repomind.core.model.code.ModuleParse
import dev.repomind.core.model.code.ParsedType
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

data class SymbolRow(
    val id: Long,
    val module: String,
    val kind: String,
    val name: String,
    val qualifiedName: String,
    val parentFqn: String?,
    val filePath: String?,
    val lineStart: Int,
    val lineEnd: Int,
    val visibility: String,
    val annotations: List<String> = emptyList(),
)

class SymbolDatabase private constructor(private val connection: Connection) : AutoCloseable {

    val edges: EdgeRepository = EdgeRepository(connection)

    init {
        connection.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS symbols (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    module TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    name TEXT NOT NULL,
                    qualified_name TEXT NOT NULL,
                    parent_fqn TEXT,
                    file_path TEXT,
                    line_start INTEGER NOT NULL DEFAULT 0,
                    line_end INTEGER NOT NULL DEFAULT 0,
                    visibility TEXT NOT NULL DEFAULT 'PACKAGE',
                    annotations TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_symbols_fqn ON symbols(qualified_name)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_symbols_name ON symbols(name)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_symbols_module ON symbols(module)")
        }
        edges.init()
        connection.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS file_state (
                    module TEXT NOT NULL,
                    path TEXT NOT NULL,
                    hash TEXT NOT NULL,
                    PRIMARY KEY (module, path)
                )
                """.trimIndent(),
            )
        }
    }

    fun fileStates(moduleName: String): Map<String, String> =
        connection.prepareStatement("SELECT path, hash FROM file_state WHERE module = ?").use { ps ->
            ps.setString(1, moduleName)
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString("path"), rs.getString("hash"))
                }
            }
        }

    fun setFileStates(moduleName: String, states: Map<String, String>) {
        connection.autoCommit = false
        try {
            connection.prepareStatement("DELETE FROM file_state WHERE module = ?").use { del ->
                del.setString(1, moduleName)
                del.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO file_state (module, path, hash) VALUES (?, ?, ?)").use { ins ->
                for ((path, hash) in states) {
                    ins.setString(1, moduleName)
                    ins.setString(2, path)
                    ins.setString(3, hash)
                    ins.addBatch()
                }
                ins.executeBatch()
            }
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    fun allModulesWithFiles(): List<String> =
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT DISTINCT module FROM file_state").use { rs ->
                buildList {
                    while (rs.next()) add(rs.getString("module"))
                }
            }
        }

    fun replaceModule(moduleName: String, parse: ModuleParse): Int {
        var inserted = 0
        connection.autoCommit = false
        try {
            connection.prepareStatement("DELETE FROM symbols WHERE module = ?").use { del ->
                del.setString(1, moduleName)
                del.executeUpdate()
            }
            val sql = """
                INSERT INTO symbols (module, kind, name, qualified_name, parent_fqn, file_path, line_start, line_end, visibility, annotations)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            connection.prepareStatement(sql).use { insert ->
                for (type in parse.types) {
                    bindAndAdd(insert, moduleName, type.kind.name, type.fqn.substringAfterLast('.'), type.fqn, null, type.filePath, type.lineStart, type.lineEnd, typeVisibility(type), type.annotations.joinToString(","))
                    inserted++
                    for (method in type.methods) {
                        bindAndAdd(insert, moduleName, "METHOD", method.name, "${type.fqn}#${method.signature}", type.fqn, type.filePath, method.line, method.line, method.visibility.name, "")
                        inserted++
                    }
                    for (field in type.fields) {
                        bindAndAdd(insert, moduleName, "FIELD", field.name, "${type.fqn}.${field.name}", type.fqn, type.filePath, field.line, field.line, field.visibility.name, "")
                        inserted++
                    }
                }
                insert.executeBatch()
            }
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
        return inserted
    }

    private fun typeVisibility(type: ParsedType): String =
        type.methods.filter { !it.synthetic }.map { it.visibility.name }.distinct().singleOrNull()
            ?: if (type.kind == dev.repomind.core.model.code.TypeKind.INTERFACE) "PUBLIC" else "PACKAGE"

    private fun bindAndAdd(
        insert: java.sql.PreparedStatement,
        module: String,
        kind: String,
        name: String,
        fqn: String,
        parentFqn: String?,
        filePath: String?,
        lineStart: Int,
        lineEnd: Int,
        visibility: String,
        annotations: String,
    ) {
        insert.setString(1, module)
        insert.setString(2, kind)
        insert.setString(3, name)
        insert.setString(4, fqn)
        if (parentFqn == null) insert.setNull(5, java.sql.Types.VARCHAR) else insert.setString(5, parentFqn)
        if (filePath == null) insert.setNull(6, java.sql.Types.VARCHAR) else insert.setString(6, filePath)
        insert.setInt(7, lineStart)
        insert.setInt(8, lineEnd)
        insert.setString(9, visibility)
        insert.setString(10, annotations)
        insert.addBatch()
    }

    fun findByFqn(fqn: String): List<SymbolRow> =
        query("SELECT $COLUMNS FROM symbols WHERE qualified_name = ?", fqn)

    fun findByNamePrefix(prefix: String, limit: Int = 100): List<SymbolRow> =
        query("SELECT $COLUMNS FROM symbols WHERE name LIKE ? ORDER BY qualified_name LIMIT ?", "$prefix%", limit)

    fun countByNamePrefix(prefix: String): Long =
        connection.prepareStatement("SELECT COUNT(*) FROM symbols WHERE name LIKE ?").use { ps ->
            ps.setString(1, "$prefix%")
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    fun findByModule(moduleName: String): List<SymbolRow> =
        query("SELECT $COLUMNS FROM symbols WHERE module = ? ORDER BY qualified_name", moduleName)

    fun allTypes(): List<SymbolRow> =
        query("SELECT $COLUMNS FROM symbols WHERE kind NOT IN ('METHOD', 'FIELD') ORDER BY qualified_name")

    fun count(): Long =
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM symbols").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private val COLUMNS = "id, module, kind, name, qualified_name, parent_fqn, file_path, line_start, line_end, visibility, annotations"

    private fun query(sql: String, vararg args: Any): List<SymbolRow> =
        connection.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, arg -> ps.setObject(i + 1, arg) }
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            SymbolRow(
                                id = rs.getLong("id"),
                                module = rs.getString("module"),
                                kind = rs.getString("kind"),
                                name = rs.getString("name"),
                                qualifiedName = rs.getString("qualified_name"),
                                parentFqn = rs.getString("parent_fqn"),
                                filePath = rs.getString("file_path"),
                                lineStart = rs.getInt("line_start"),
                                lineEnd = rs.getInt("line_end"),
                                visibility = rs.getString("visibility"),
                                annotations = rs.getString("annotations").split(',').filter { it.isNotBlank() },
                            ),
                        )
                    }
                }
            }
        }

    override fun close() {
        connection.close()
    }

    companion object {
        fun open(dbPath: Path): SymbolDatabase {
            Files.createDirectories(dbPath.toAbsolutePath().parent)
            Class.forName("org.sqlite.JDBC")
            val conn = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
            conn.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            return SymbolDatabase(conn)
        }
    }
}

