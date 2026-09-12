package dev.repomind.storage.sqlite

import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import dev.repomind.core.model.code.ModuleParse
import dev.repomind.core.model.code.ParsedType
import dev.repomind.core.model.code.TypeKind
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PartitionedSymbolDatabaseTest {

    @Test
    fun `parallel workers write to separate module partitions without lock contention`() {
        val tempDir = Files.createTempDirectory("repomind-part-test")
        val partDb = PartitionedSymbolDatabase(tempDir.resolve("partitions"))

        val workerCount = 6
        val executor = Executors.newFixedThreadPool(workerCount)
        val successCounter = AtomicInteger(0)

        for (i in 0 until workerCount) {
            val modName = "module-$i"
            executor.submit {
                val db = partDb.openPartition(modName)
                db.recordModule(modName, "/path/$modName", "GRADLE")
                val parse = ModuleParse(
                    moduleName = modName,
                    types = listOf(
                        ParsedType(
                            fqn = "com.example.mod$i.Service$i",
                            kind = TypeKind.CLASS,
                            packageName = "com.example.mod$i",
                            filePath = "Service$i.java",
                            lineStart = 1,
                            lineEnd = 25,
                            annotations = emptyList(),
                            superTypeFqn = null,
                            interfaceFqns = emptyList(),
                            methods = emptyList(),
                            fields = emptyList(),
                        ),
                    ),
                    unresolvedSymbols = emptyList(),
                    edges = listOf(
                        DependencyEdge(
                            sourceFqn = "com.example.mod$i.Service$i",
                            targetFqn = "com.example.common.SharedUtil",
                            kind = EdgeKind.CALLS,
                            confidence = Confidence.CONFIRMED,
                        ),
                    ),
                )
                db.replaceModule(modName, parse)
                db.edges.replaceModule(modName, parse.edges)
                successCounter.incrementAndGet()
            }
        }

        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(workerCount, successCounter.get())

        val partitions = partDb.listPartitions()
        assertEquals(workerCount, partitions.size)

        // Merge all into master database
        val masterDbFile = tempDir.resolve("master.db")
        SymbolDatabase.open(masterDbFile).use { masterDb ->
            val mergedCount = partDb.mergeAllInto(masterDb)
            assertEquals(workerCount, mergedCount)

            val report = masterDb.confidenceReport()
            assertEquals(workerCount.toLong(), report.totalSymbols)
            assertEquals(workerCount.toLong(), report.totalEdges)

            // Verify master CTE blast radius queries work across all merged partitions
            val callers = masterDb.edges.transitiveCallers("com.example.common.SharedUtil")
            assertEquals(workerCount, callers.size)
            for (i in 0 until workerCount) {
                assertTrue(callers.contains("com.example.mod$i.Service$i"))
            }
        }

        partDb.close()
    }
}
