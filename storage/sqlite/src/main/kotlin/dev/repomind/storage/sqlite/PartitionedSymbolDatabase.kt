package dev.repomind.storage.sqlite

import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.ModuleParse
import dev.repomind.core.model.code.ParsedType
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Virtual partitioning manager for large mono-repositories (100,000+ files).
 * Allows individual modules to be indexed concurrently into dedicated SQLite partition files
 * without write-lock contention, followed by unified querying and fast merge into a master database.
 */
class PartitionedSymbolDatabase(
    val partitionsDir: Path
) : AutoCloseable {

    init {
        Files.createDirectories(partitionsDir)
    }

    private val activePartitions = ConcurrentHashMap<String, SymbolDatabase>()

    fun sanitizeModuleName(moduleName: String): String {
        return moduleName.replace(":", "_").replace("/", "_").replace("\\", "_")
    }

    fun partitionPath(moduleName: String): Path {
        val sanitized = sanitizeModuleName(moduleName)
        return partitionsDir.resolve("$sanitized.db")
    }

    /**
     * Opens or retrieves an open SymbolDatabase for a specific partition.
     * Multiple threads can write to different partitions concurrently without lock contention.
     */
    fun openPartition(moduleName: String, readOnly: Boolean = false): SymbolDatabase {
        return activePartitions.compute(moduleName) { _, existing ->
            if (existing != null) existing
            else SymbolDatabase.open(partitionPath(moduleName), readOnly = readOnly)
        }!!
    }

    fun listPartitions(): List<String> {
        if (!Files.isDirectory(partitionsDir)) return emptyList()
        return Files.list(partitionsDir).use { stream ->
            stream.filter { it.toString().endsWith(".db") && !it.toString().contains("-wal") && !it.toString().contains("-shm") }
                .map { it.fileName.toString().removeSuffix(".db") }
                .sorted()
                .toList()
        }
    }

    fun deletePartition(moduleName: String): Boolean {
        val db = activePartitions.remove(moduleName)
        db?.close()
        val path = partitionPath(moduleName)
        val deletedDb = Files.deleteIfExists(path)
        Files.deleteIfExists(Path.of("$path-wal"))
        Files.deleteIfExists(Path.of("$path-shm"))
        return deletedDb
    }

    /**
     * Merges all partition databases into the master target database using SQLite ATTACH DATABASE,
     * falling back to batch statement copying.
     */
    fun mergeAllInto(targetDb: SymbolDatabase): Int {
        var mergedCount = 0
        val partitions = if (Files.isDirectory(partitionsDir)) {
            Files.list(partitionsDir).use { stream ->
                stream.filter { it.toString().endsWith(".db") && !it.toString().contains("-wal") && !it.toString().contains("-shm") }
                    .toList()
            }
        } else emptyList()

        for (p in partitions) {
            val success = tryAttachMerge(targetDb, p)
            if (!success) {
                fallbackBatchMerge(targetDb, p)
            }
            mergedCount++
        }
        return mergedCount
    }

    private fun tryAttachMerge(targetDb: SymbolDatabase, partitionPath: Path): Boolean {
        return targetDb.withWriteLock {
            try {
                val escapedPath = partitionPath.toAbsolutePath().normalize().toString().replace('\\', '/')
                targetDb.execute("ATTACH DATABASE '$escapedPath' AS part_src")
                targetDb.execute("INSERT OR REPLACE INTO modules SELECT * FROM part_src.modules")
                targetDb.execute(
                    """
                    INSERT OR REPLACE INTO symbols (
                        module, kind, name, qualified_name, parent_fqn, file_path, line_start, line_end, visibility, annotations, signature_hash
                    ) SELECT module, kind, name, qualified_name, parent_fqn, file_path, line_start, line_end, visibility, annotations, signature_hash 
                      FROM part_src.symbols
                    """.trimIndent()
                )
                targetDb.execute(
                    """
                    INSERT OR REPLACE INTO graph_edges (
                        source_fqn, target_fqn, kind, confidence, line, caller_member
                    ) SELECT source_fqn, target_fqn, kind, confidence, line, caller_member 
                      FROM part_src.graph_edges
                    """.trimIndent()
                )
                try {
                    targetDb.execute("INSERT OR REPLACE INTO file_state SELECT * FROM part_src.file_state")
                } catch (_: Exception) {}
                targetDb.execute("DETACH DATABASE part_src")
                true
            } catch (_: Exception) {
                try { targetDb.execute("DETACH DATABASE part_src") } catch (_: Exception) {}
                false
            }
        }
    }

    private fun fallbackBatchMerge(targetDb: SymbolDatabase, partitionPath: Path) {
        SymbolDatabase.open(partitionPath, readOnly = true).use { sourceDb ->
            val modules = sourceDb.allRecordedModules()
            for (mod in modules) {
                val symbols = sourceDb.symbolsIn(mod)
                val edges = sourceDb.edges.allEdges().filter { edge ->
                    symbols.any { s -> s.qualifiedName == edge.sourceFqn || edge.sourceFqn.startsWith("${s.qualifiedName}#") }
                }
                val fileStates = sourceDb.fileStates(mod)

                val parse = ModuleParse(
                    moduleName = mod,
                    types = emptyList(),
                    unresolvedSymbols = emptyList(),
                    edges = edges,
                )
                targetDb.replaceModule(mod, parse)
                targetDb.edges.replaceModule(mod, edges)
                targetDb.setFileStates(mod, fileStates)
            }
        }
    }

    override fun close() {
        for ((_, db) in activePartitions) {
            try { db.close() } catch (_: Exception) {}
        }
        activePartitions.clear()
    }

    companion object {
        fun open(partitionsDir: Path): PartitionedSymbolDatabase = PartitionedSymbolDatabase(partitionsDir)
    }
}
