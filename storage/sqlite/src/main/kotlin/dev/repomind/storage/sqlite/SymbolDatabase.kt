package dev.repomind.storage.sqlite

import dev.repomind.core.model.RepoMindLimits
import dev.repomind.core.model.code.ModuleParse
import dev.repomind.core.model.code.ParsedType
import dev.repomind.core.model.code.UnresolvedSymbol
import dev.repomind.core.model.sha256Of
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
    val signatureHash: String? = null,
)

data class ConfidenceReport(
    val totalSymbols: Long,
    val totalEdges: Long,
    val totalUnresolved: Long,
    val confidenceRate: Double,
)

class SymbolDatabase private constructor(
    private val connection: Connection,
    val readOnly: Boolean = false,
) : AutoCloseable {

    val edges: EdgeRepository = EdgeRepository(connection)
    val graphStore: dev.repomind.core.graph.GraphStore get() = edges

    init {
        if (!readOnly) {
            connection.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS modules (
                    name TEXT PRIMARY KEY,
                    path TEXT NOT NULL,
                    build_system TEXT NOT NULL DEFAULT 'UNKNOWN'
                )
                """.trimIndent(),
            )
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS files (
                    path TEXT NOT NULL,
                    module TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    last_indexed_at INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (module, path)
                )
                """.trimIndent(),
            )
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_files_hash ON files(content_hash)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_files_module ON files(module)")

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
                    annotations TEXT NOT NULL DEFAULT '',
                    signature_hash TEXT
                )
                """.trimIndent(),
            )
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_symbols_fqn ON symbols(qualified_name)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_symbols_name ON symbols(name)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_symbols_module ON symbols(module)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_symbols_file_path ON symbols(file_path)")
            try {
                stmt.executeUpdate("ALTER TABLE symbols ADD COLUMN signature_hash TEXT")
            } catch (_: Exception) {
            }

            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS unresolved_symbols (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    module TEXT NOT NULL,
                    symbol TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    line INTEGER NOT NULL DEFAULT 0,
                    reason TEXT
                )
                """.trimIndent(),
            )
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_unresolved_module ON unresolved_symbols(module)")

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
        edges.init()
    }
}

    fun recordModule(name: String, path: String, buildSystem: String = "UNKNOWN") {
        connection.prepareStatement("INSERT OR REPLACE INTO modules (name, path, build_system) VALUES (?, ?, ?)").use { ps ->
            ps.setString(1, name)
            ps.setString(2, path)
            ps.setString(3, buildSystem)
            ps.executeUpdate()
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
        val now = System.currentTimeMillis()
        connection.autoCommit = false
        try {
            connection.prepareStatement("DELETE FROM file_state WHERE module = ?").use { del ->
                del.setString(1, moduleName)
                del.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM files WHERE module = ?").use { del ->
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
            connection.prepareStatement("INSERT INTO files (module, path, content_hash, last_indexed_at) VALUES (?, ?, ?, ?)").use { ins ->
                for ((path, hash) in states) {
                    ins.setString(1, moduleName)
                    ins.setString(2, path)
                    ins.setString(3, hash)
                    ins.setLong(4, now)
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

    fun deleteModule(moduleName: String) {
        connection.autoCommit = false
        try {
            connection.prepareStatement("DELETE FROM symbols WHERE module = ?").use {
                it.setString(1, moduleName); it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM unresolved_symbols WHERE module = ?").use {
                it.setString(1, moduleName); it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM file_state WHERE module = ?").use {
                it.setString(1, moduleName); it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM files WHERE module = ?").use {
                it.setString(1, moduleName); it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM modules WHERE name = ?").use {
                it.setString(1, moduleName); it.executeUpdate()
            }
            edges.deleteModule(moduleName)
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
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
            connection.prepareStatement("DELETE FROM unresolved_symbols WHERE module = ?").use { del ->
                del.setString(1, moduleName)
                del.executeUpdate()
            }
            val sql = """
                INSERT INTO symbols (module, kind, name, qualified_name, parent_fqn, file_path, line_start, line_end, visibility, annotations, signature_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            connection.prepareStatement(sql).use { insert ->
                for (type in parse.types) {
                    bindAndAdd(insert, moduleName, type.kind.name, type.fqn.substringAfterLast('.'), type.fqn, null, type.filePath, type.lineStart, type.lineEnd, typeVisibility(type), type.annotations.joinToString(","), null)
                    inserted++
                    for (method in type.methods) {
                        val sigHash = sha256Of(method.signature).take(16)
                        bindAndAdd(insert, moduleName, "METHOD", method.name, "${type.fqn}#${method.signature}", type.fqn, type.filePath, method.line, method.line, method.visibility.name, method.annotations.joinToString(","), sigHash)
                        inserted++
                    }
                    for (field in type.fields) {
                        bindAndAdd(insert, moduleName, "FIELD", field.name, "${type.fqn}.${field.name}", type.fqn, type.filePath, field.line, field.line, field.visibility.name, field.annotations.joinToString(","), null)
                        inserted++
                    }
                }
                insert.executeBatch()
            }

            if (parse.unresolvedSymbols.isNotEmpty()) {
                val unresSql = "INSERT INTO unresolved_symbols (module, symbol, file_path, line, reason) VALUES (?, ?, ?, ?, ?)"
                connection.prepareStatement(unresSql).use { unresInsert ->
                    for (unres in parse.unresolvedSymbols) {
                        unresInsert.setString(1, moduleName)
                        unresInsert.setString(2, unres.symbol)
                        unresInsert.setString(3, unres.filePath)
                        unresInsert.setInt(4, unres.line)
                        if (unres.reason == null) unresInsert.setNull(5, java.sql.Types.VARCHAR) else unresInsert.setString(5, unres.reason)
                        unresInsert.addBatch()
                    }
                    unresInsert.executeBatch()
                }
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

    fun countUnresolved(moduleName: String? = null): Long {
        val sql = if (moduleName == null) "SELECT COUNT(*) FROM unresolved_symbols" else "SELECT COUNT(*) FROM unresolved_symbols WHERE module = ?"
        return connection.prepareStatement(sql).use { ps ->
            if (moduleName != null) ps.setString(1, moduleName)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    fun unresolvedSymbols(moduleName: String? = null): List<UnresolvedSymbol> {
        val sql = if (moduleName == null) "SELECT symbol, file_path, line, reason FROM unresolved_symbols ORDER BY id"
            else "SELECT symbol, file_path, line, reason FROM unresolved_symbols WHERE module = ? ORDER BY id"
        return connection.prepareStatement(sql).use { ps ->
            if (moduleName != null) ps.setString(1, moduleName)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            UnresolvedSymbol(
                                symbol = rs.getString("symbol"),
                                filePath = rs.getString("file_path"),
                                line = rs.getInt("line"),
                                reason = rs.getString("reason"),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun confidenceReport(moduleName: String? = null): ConfidenceReport {
        val symCount = if (moduleName == null) count() else findByModule(moduleName).size.toLong()
        val edgeCount = edges.count()
        val unresCount = countUnresolved(moduleName)
        val total = symCount + unresCount
        val rate = if (total > 0) symCount.toDouble() / total.toDouble() else 1.0
        return ConfidenceReport(
            totalSymbols = symCount,
            totalEdges = edgeCount,
            totalUnresolved = unresCount,
            confidenceRate = (rate * 1000.0).toInt() / 1000.0,
        )
    }

    fun typeToModuleMap(): Map<String, String> =
        connection.prepareStatement("SELECT qualified_name, module FROM symbols WHERE kind NOT IN ('METHOD', 'FIELD')").use { ps ->
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        put(rs.getString("qualified_name"), rs.getString("module"))
                    }
                }
            }
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
        signatureHash: String?,
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
        if (signatureHash == null) insert.setNull(11, java.sql.Types.VARCHAR) else insert.setString(11, signatureHash)
        insert.addBatch()
    }

    fun findByFqn(fqn: String): List<SymbolRow> =
        query("SELECT $COLUMNS FROM symbols WHERE qualified_name = ?", fqn)

    fun findByNamePrefix(prefix: String, limit: Int = RepoMindLimits.DEFAULT_SYMBOL_SEARCH_LIMIT): List<SymbolRow> =
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

    fun findByFilePath(filePath: String): List<SymbolRow> {
        val forward = filePath.replace('\\', '/')
        val backward = filePath.replace('/', '\\')
        val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')
        return query(
            "SELECT $COLUMNS FROM symbols WHERE file_path = ? OR file_path = ? OR file_path LIKE ? OR file_path LIKE ? OR file_path LIKE ? ORDER BY line_start",
            forward,
            backward,
            "%$forward",
            "%$backward",
            "%$fileName",
        )
    }

    fun allTypes(): List<SymbolRow> =
        query("SELECT $COLUMNS FROM symbols WHERE kind NOT IN ('METHOD', 'FIELD') ORDER BY qualified_name")

    fun count(): Long =
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM symbols").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private val COLUMNS = "id, module, kind, name, qualified_name, parent_fqn, file_path, line_start, line_end, visibility, annotations, signature_hash"

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
                                signatureHash = rs.getString("signature_hash"),
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
        fun open(dbPath: Path, readOnly: Boolean = false): SymbolDatabase {
            if (!readOnly) {
                Files.createDirectories(dbPath.toAbsolutePath().parent)
            }
            Class.forName("org.sqlite.JDBC")
            val config = org.sqlite.SQLiteConfig()
            if (readOnly) {
                config.setReadOnly(true)
            }
            val conn = config.createConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
            conn.createStatement().use { stmt ->
                if (readOnly) {
                    stmt.execute("PRAGMA query_only = ON;")
                } else {
                    stmt.execute("PRAGMA journal_mode=WAL;")
                }
            }
            return SymbolDatabase(conn, readOnly)
        }
    }
}
