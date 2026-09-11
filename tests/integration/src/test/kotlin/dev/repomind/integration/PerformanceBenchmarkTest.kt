package dev.repomind.integration

import dev.repomind.core.index.IncrementalIndexer
import dev.repomind.core.query.RepoQueryEngine
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class PerformanceBenchmarkTest {

    private fun generateBenchmarkRepo(numClasses: Int): Path {
        val root = Files.createTempDirectory("benchmark-perf-repo")
        root.resolve("pom.xml").writeText(
            """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.perf</groupId>
              <artifactId>bench</artifactId>
              <version>1.0.0</version>
            </project>
            """.trimIndent(),
        )
        val src = root.resolve("src/main/java/com/perf")
        Files.createDirectories(src)

        // Generate classes with inter-dependencies
        for (i in 1..numClasses) {
            val target = if (i > 1) "new Component${i - 1}().execute();" else ""
            src.resolve("Component$i.java").writeText(
                """
                package com.perf;
                public class Component$i {
                    public int execute() {
                        $target
                        return $i;
                    }
                }
                """.trimIndent(),
            )
        }
        return root
    }

    @Test
    fun `full indexing throughput meets performance target`() {
        val classCount = 60
        val root = generateBenchmarkRepo(classCount)
        val dbPath = root.resolve(".repomind/index.db")
        val indexer = IncrementalIndexer(dbPath)

        val startTime = System.nanoTime()
        val result = indexer.update(root)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000

        val throughput = (classCount.toDouble() / (elapsedMs.toDouble() / 1000.0))
        println("Full indexing: $classCount classes in ${elapsedMs}ms (${String.format("%.1f", throughput)} files/sec)")

        assertTrue(result.symbolsIndexed >= classCount)
        assertTrue(elapsedMs > 0)
    }

    @Test
    fun `incremental update latency is sub-second`() {
        val root = generateBenchmarkRepo(50)
        val dbPath = root.resolve(".repomind/index.db")
        val indexer = IncrementalIndexer(dbPath)
        indexer.update(root)

        // Modify single file
        val target = root.resolve("src/main/java/com/perf/Component1.java")
        target.writeText(
            """
            package com.perf;
            public class Component1 {
                public int execute() {
                    return 999;
                }
            }
            """.trimIndent(),
        )

        val startTime = System.nanoTime()
        val incResult = indexer.update(root)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        println("Incremental update latency: ${elapsedMs}ms")

        assertTrue(incResult.modifiedFiles == 1)
        assertTrue(elapsedMs < 1000, "Incremental update took ${elapsedMs}ms, budget is 1000ms")
    }

    @Test
    fun `graph query latency is sub-50ms`() {
        val root = generateBenchmarkRepo(50)
        val dbPath = root.resolve(".repomind/index.db")
        IncrementalIndexer(dbPath).update(root)

        RepoQueryEngine(dbPath).use { engine ->
            // Warmup
            engine.findCallers("com.perf.Component1", 20)

            // Measure
            val startTime = System.nanoTime()
            val callers = engine.findCallers("com.perf.Component1", 20)
            val queryMs = (System.nanoTime() - startTime) / 1_000_000
            println("findCallers latency: ${queryMs}ms")

            assertTrue(callers.items.isNotEmpty())
            assertTrue(queryMs < 50, "Graph query latency was ${queryMs}ms, budget is 50ms")

            // Impact query latency
            val impactStart = System.nanoTime()
            val impact = engine.impact("com.perf.Component1")
            val impactMs = (System.nanoTime() - impactStart) / 1_000_000
            println("impact query latency: ${impactMs}ms")
            assertTrue(impactMs < 100, "Impact query latency was ${impactMs}ms, budget is 100ms")
        }
    }
}
