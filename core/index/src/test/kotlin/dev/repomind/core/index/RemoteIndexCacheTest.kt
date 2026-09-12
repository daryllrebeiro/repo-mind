package dev.repomind.core.index

import dev.repomind.core.index.remote.BundleManifest
import dev.repomind.core.index.remote.CasIndexBundle
import dev.repomind.core.index.remote.DirectoryRemoteStorage
import dev.repomind.core.index.remote.RemoteIndexCacheManager
import dev.repomind.core.index.remote.RemoteIndexStorage
import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import dev.repomind.core.model.code.ModuleParse
import dev.repomind.core.model.code.ParsedType
import dev.repomind.core.model.code.TypeKind
import dev.repomind.storage.sqlite.SymbolDatabase
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteIndexCacheTest {

    @Test
    fun `pack and unpack bundle preserves database contents and manifest metadata`() {
        val tempDir = Files.createTempDirectory("cas-bundle-test")
        val dbPath = tempDir.resolve("original-index.db")

        SymbolDatabase.open(dbPath).use { db ->
            db.recordModule("test-mod", tempDir.toString(), "GRADLE")
            val parse = ModuleParse(
                moduleName = "test-mod",
                types = listOf(
                    ParsedType(
                        fqn = "com.example.OrderService",
                        kind = TypeKind.CLASS,
                        packageName = "com.example",
                        filePath = "OrderService.java",
                        lineStart = 1,
                        lineEnd = 20,
                        annotations = emptyList(),
                        superTypeFqn = null,
                        interfaceFqns = emptyList(),
                        methods = emptyList(),
                        fields = emptyList(),
                    ),
                ),
                unresolvedSymbols = emptyList(),
                edges = listOf(
                    DependencyEdge("com.example.OrderService", "com.example.OrderRepo", EdgeKind.CALLS, Confidence.CONFIRMED),
                ),
            )
            db.replaceModule("test-mod", parse)
            db.edges.replaceModule("test-mod", parse.edges)
        }

        val manifest = BundleManifest(
            commitSha = "abc1234567890",
            contentHash = "deadbeef001122",
            timestamp = System.currentTimeMillis(),
            moduleCount = 1,
            symbolCount = 1,
            edgeCount = 1,
            metadata = mapOf("branch" to "main", "builder" to "repomind-ci"),
        )

        val bundleFile = tempDir.resolve("cache/abc1234567890.repomind")
        val hash = CasIndexBundle.pack(dbPath, manifest, bundleFile)
        assertTrue(Files.isRegularFile(bundleFile))
        assertTrue(hash.isNotBlank())

        val extractedDbPath = tempDir.resolve("restored/index.db")
        val unpackedManifest = CasIndexBundle.unpack(bundleFile, extractedDbPath)

        assertEquals("abc1234567890", unpackedManifest.commitSha)
        assertEquals("deadbeef001122", unpackedManifest.contentHash)
        assertEquals(1, unpackedManifest.moduleCount)
        assertEquals("main", unpackedManifest.metadata["branch"])

        SymbolDatabase.open(extractedDbPath).use { db ->
            val report = db.confidenceReport()
            assertEquals(1L, report.totalSymbols)
            assertEquals(1L, report.totalEdges)
            val callers = db.edges.transitiveCallers("com.example.OrderRepo")
            assertTrue(callers.contains("com.example.OrderService"))
        }
    }

    @Test
    fun `remote cache manager pushes and pulls index bundles with cache hits and misses`() {
        val repoRoot = Files.createTempDirectory("repo-root")
        val remoteStorageDir = Files.createTempDirectory("remote-storage")
        val dbPath = repoRoot.resolve(".repomind/index.db")
        Files.createDirectories(dbPath.parent)

        // Seed some files in repoRoot
        Files.writeString(repoRoot.resolve("Service.java"), "public class Service {}")

        SymbolDatabase.open(dbPath).use { db ->
            db.recordModule("core", repoRoot.toString(), "GRADLE")
            val parse = ModuleParse(
                moduleName = "core",
                types = listOf(
                    ParsedType(
                        fqn = "com.example.Service",
                        kind = TypeKind.CLASS,
                        packageName = "com.example",
                        filePath = "Service.java",
                        lineStart = 1,
                        lineEnd = 10,
                        annotations = emptyList(),
                        superTypeFqn = null,
                        interfaceFqns = emptyList(),
                        methods = emptyList(),
                        fields = emptyList(),
                    ),
                ),
                unresolvedSymbols = emptyList(),
                edges = emptyList(),
            )
            db.replaceModule("core", parse)
        }

        val storage = RemoteIndexStorage.resolve(remoteStorageDir.toUri().toString())
        assertTrue(storage is DirectoryRemoteStorage)

        val cacheManager = RemoteIndexCacheManager(storage)
        val fixedSha = "f00b4711223344"

        // 1. Pull before push -> cache miss
        val targetRestoreDb = repoRoot.resolve(".repomind/restored.db")
        val missManifest = cacheManager.pullIndex(repoRoot, targetRestoreDb, commitSha = fixedSha)
        assertNull(missManifest)
        assertFalse(Files.exists(targetRestoreDb))

        // 2. Push index to remote
        val pushedManifest = cacheManager.pushIndex(repoRoot, dbPath, commitSha = fixedSha, metadata = mapOf("ci" to "true"))
        assertNotNull(pushedManifest)
        assertEquals(fixedSha, pushedManifest.commitSha)
        assertEquals(1, pushedManifest.moduleCount)

        // 3. Pull after push -> cache hit
        val hitManifest = cacheManager.pullIndex(repoRoot, targetRestoreDb, commitSha = fixedSha)
        assertNotNull(hitManifest)
        assertEquals(fixedSha, hitManifest.commitSha)
        assertTrue(Files.isRegularFile(targetRestoreDb))

        SymbolDatabase.open(targetRestoreDb).use { db ->
            assertEquals(1L, db.confidenceReport().totalSymbols)
        }
    }
}
