package dev.repomind.core.index

import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.code.EdgeKind
import dev.repomind.core.scanner.RepositoryScanner
import dev.repomind.language.java.JavaSemanticParser
import dev.repomind.storage.sqlite.SymbolDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

fun sha256Of(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

class IncrementalIndexer(private val dbPath: Path) {

    fun update(repoRoot: Path, parser: JavaSemanticParser = JavaSemanticParser()): IncrementalResult {
        val startedAt = System.nanoTime()
        val root = repoRoot.toAbsolutePath().normalize()
        val scan = RepositoryScanner().scan(root)

        SymbolDatabase.open(dbPath).use { db ->
            var added = 0
            var modified = 0
            var deleted = 0

            val knownModules = db.allModulesWithFiles().toSet()
            val currentByModule = scan.modules.associateWith { module -> currentFileHashes(module, root) }
            val changedModules = mutableSetOf<RepoModule>()

            for ((module, current) in currentByModule) {
                val previous = if (module.name in knownModules) db.fileStates(module.name) else emptyMap()

                for (path in current.keys) {
                    when {
                        path !in previous -> added++
                        previous[path] != current[path] -> modified++
                    }
                }
                deleted += previous.keys.count { it !in current.keys }

                val changed = previous.keys != current.keys ||
                    current.any { (path, hash) -> previous[path] != hash }
                if (changed) changedModules += module
            }

            val invalidated = crossModuleInvalidation(db, currentByModule, changedModules)
            val toReindex = (changedModules + invalidated).distinctBy { it.name }
            val fullIndex = db.count() == 0L

            var symbols = 0
            var edges = 0
            for (module in toReindex.sortedBy { it.name }) {
                val parsed = parser.parseModule(module, emptyList())
                symbols += db.replaceModule(module.name, parsed)
                edges += db.edges.replaceModule(module.name, parsed.edges)
                db.setFileStates(module.name, currentByModule[module].orEmpty())
            }

            return IncrementalResult(
                addedFiles = added,
                modifiedFiles = modified,
                deletedFiles = deleted,
                reparsedModules = toReindex.map { it.name }.sorted(),
                skippedModules = scan.modules.size - toReindex.size,
                symbolsIndexed = symbols,
                edgesIndexed = edges,
                fullIndex = fullIndex,
                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
            )
        }
    }

    private fun currentFileHashes(module: RepoModule, repoRoot: Path): Map<String, String> =
        buildMap {
            for (sourceRoot in module.sourceRoots) {
                sourceRoot.path.toFile().walkTopDown()
                    .filter { it.isFile && it.extension in RepositoryScanner.JAVA_EXTENSIONS }
                    .forEach { file ->
                        val rel = repoRoot.relativize(file.toPath()).toString().replace('\\', '/')
                        put(rel, sha256Of(Files.readAllBytes(file.toPath())))
                    }
            }
        }

    private fun crossModuleInvalidation(
        db: SymbolDatabase,
        currentByModule: Map<RepoModule, Map<String, String>>,
        changedModules: Set<RepoModule>,
    ): Set<RepoModule> {
        if (changedModules.isEmpty()) return emptySet()
        val changedSimpleNames = changedModules.flatMap { changed ->
            currentByModule[changed].orEmpty().keys.map { it.substringAfterLast('/').substringBeforeLast('.') }
        }.toSet()
        if (changedSimpleNames.isEmpty()) return emptySet()

        val structuralKinds = setOf(EdgeKind.IMPORTS, EdgeKind.USES, EdgeKind.CALLS, EdgeKind.EXTENDS)
        val changedModuleNames = changedModules.map { it.name }.toSet()

        return db.edges.findAll()
            .asSequence()
            .filter { it.module !in changedModuleNames && EdgeKind.valueOf(it.kind) in structuralKinds }
            .filter { it.targetFqn.substringBefore('#').substringAfterLast('.') in changedSimpleNames }
            .mapNotNull { edgeRow -> currentByModule.keys.firstOrNull { it.name == edgeRow.module } }
            .toSet()
    }
}
