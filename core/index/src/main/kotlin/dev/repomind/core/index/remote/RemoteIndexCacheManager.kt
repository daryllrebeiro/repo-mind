package dev.repomind.core.index.remote

import dev.repomind.core.model.sha256Of
import dev.repomind.core.scanner.RepositoryScanner
import dev.repomind.storage.sqlite.SymbolDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class RemoteIndexCacheManager(
    val storage: RemoteIndexStorage
) {

    fun pushIndex(
        repoRoot: Path,
        dbPath: Path,
        commitSha: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ): BundleManifest? {
        if (!Files.isRegularFile(dbPath)) return null

        val sha = commitSha ?: resolveGitSha(repoRoot) ?: computeTreeHash(repoRoot)
        val stats = SymbolDatabase.open(dbPath).use { db ->
            val report = db.confidenceReport()
            val recorded = db.allRecordedModules()
            val moduleCount = if (recorded.isNotEmpty()) recorded.size else db.allModulesWithFiles().size
            Triple(moduleCount, report.totalSymbols, report.totalEdges)
        }

        val manifest = BundleManifest(
            commitSha = sha,
            contentHash = computeTreeHash(repoRoot),
            timestamp = System.currentTimeMillis(),
            moduleCount = stats.first,
            symbolCount = stats.second,
            edgeCount = stats.third,
            metadata = metadata,
        )

        val tempBundle = Files.createTempFile("repomind-push-", ".bundle")
        try {
            CasIndexBundle.pack(dbPath, manifest, tempBundle)
            storage.push(sha, tempBundle)
            return manifest
        } finally {
            Files.deleteIfExists(tempBundle)
        }
    }

    fun pullIndex(
        repoRoot: Path,
        targetDbPath: Path,
        commitSha: String? = null,
    ): BundleManifest? {
        val sha = commitSha ?: resolveGitSha(repoRoot) ?: computeTreeHash(repoRoot)
        val tempBundle = Files.createTempFile("repomind-pull-", ".bundle")
        try {
            val found = storage.pull(sha, tempBundle)
            if (!found) return null
            return CasIndexBundle.unpack(tempBundle, targetDbPath)
        } catch (_: Exception) {
            return null
        } finally {
            Files.deleteIfExists(tempBundle)
        }
    }

    companion object {

        fun resolveGitSha(repoRoot: Path): String? {
            // 1. Check CI environment variables
            val envSha = System.getenv("GIT_COMMIT")
                ?: System.getenv("GITHUB_SHA")
                ?: System.getenv("CI_COMMIT_SHA")
            if (!envSha.isNullOrBlank()) return envSha.trim()

            // 2. Inspect .git directory
            val gitDir = repoRoot.resolve(".git")
            if (Files.isDirectory(gitDir)) {
                val headFile = gitDir.resolve("HEAD")
                if (Files.isRegularFile(headFile)) {
                    val headContent = Files.readString(headFile).trim()
                    if (headContent.startsWith("ref: ")) {
                        val refPath = headContent.removePrefix("ref: ").trim()
                        val targetRef = gitDir.resolve(refPath)
                        if (Files.isRegularFile(targetRef)) {
                            return Files.readString(targetRef).trim()
                        }
                    } else if (headContent.length in 40..64 && headContent.all { it.isLetterOrDigit() }) {
                        return headContent
                    }
                }
            }

            // 3. Fall back to running git rev-parse HEAD
            return try {
                val process = ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(repoRoot.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                val exited = process.waitFor(3, TimeUnit.SECONDS)
                if (exited && process.exitValue() == 0) {
                    process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotBlank() }
                } else null
            } catch (_: Exception) {
                null
            }
        }

        fun computeTreeHash(repoRoot: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val files = mutableListOf<File>()
            repoRoot.toFile().walkTopDown()
                .filter { it.isFile && !it.path.contains(".git") && !it.path.contains(".repomind") }
                .forEach { files += it }

            files.sortBy { it.relativeTo(repoRoot.toFile()).path.replace('\\', '/') }
            for (f in files) {
                val rel = f.relativeTo(repoRoot.toFile()).path.replace('\\', '/')
                digest.update(rel.toByteArray(Charsets.UTF_8))
                digest.update(f.lastModified().toString().toByteArray(Charsets.UTF_8))
                digest.update(f.length().toString().toByteArray(Charsets.UTF_8))
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
