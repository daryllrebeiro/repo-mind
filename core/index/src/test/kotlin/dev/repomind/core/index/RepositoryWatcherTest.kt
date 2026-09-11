package dev.repomind.core.index

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryWatcherTest {

    private fun sampleRepo(): Path {
        val root = Files.createTempDirectory("repomind-watch-test")
        root.resolve("pom.xml").writeText(
            """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.test</groupId>
              <artifactId>watch</artifactId>
              <version>1.0.0</version>
            </project>
            """.trimIndent(),
        )
        val src = root.resolve("src/main/java/com/test")
        Files.createDirectories(src)
        src.resolve("Hello.java").writeText(
            """
            package com.test;
            public class Hello {
                public String say() { return "hello"; }
            }
            """.trimIndent(),
        )
        return root
    }

    @Test
    fun `isIgnored correctly filters internal and ephemeral directories`() {
        val root = sampleRepo()
        val indexer = IncrementalIndexer(root.resolve(".repomind/index.db"))
        val watcher = RepositoryWatcher(root, indexer)

        assertTrue(watcher.isIgnored(root.resolve(".git")))
        assertTrue(watcher.isIgnored(root.resolve(".git/HEAD")))
        assertTrue(watcher.isIgnored(root.resolve(".repomind")))
        assertTrue(watcher.isIgnored(root.resolve(".repomind/index.db")))
        assertTrue(watcher.isIgnored(root.resolve("build")))
        assertTrue(watcher.isIgnored(root.resolve("build/classes/java/main")))
        assertTrue(watcher.isIgnored(root.resolve("target")))
        assertTrue(watcher.isIgnored(root.resolve(".idea")))

        assertFalse(watcher.isIgnored(root.resolve("src/main/java/com/test/Hello.java")))
        assertFalse(watcher.isIgnored(root.resolve("src/main/resources/application.properties")))
    }

    @Test
    fun `watcher detects file modifications and triggers incremental update with debounce`() {
        val root = sampleRepo()
        val dbPath = root.resolve(".repomind/index.db")
        val indexer = IncrementalIndexer(dbPath)
        // Initial index
        indexer.update(root)

        val latch = CountDownLatch(1)
        var updatedResult: IncrementalResult? = null

        val watcher = RepositoryWatcher(
            repoRoot = root,
            indexer = indexer,
            debounceMs = 150L,
            onUpdate = { result ->
                updatedResult = result
                latch.countDown()
            },
        )

        val watcherThread = thread(start = true, isDaemon = true) {
            watcher.start(maxIterations = 1)
        }

        // Allow watch service to initialize and register directory keys
        Thread.sleep(300)

        // Modify file to trigger watcher event
        val file = root.resolve("src/main/java/com/test/Hello.java")
        file.writeText(
            """
            package com.test;
            public class Hello {
                public String say() { return "world!"; }
                public int version() { return 2; }
            }
            """.trimIndent(),
        )

        val triggered = latch.await(8, TimeUnit.SECONDS)
        watcher.stop()
        watcherThread.join(2000)

        assertTrue(triggered, "File watcher did not trigger within timeout")
        val res = updatedResult
        assertTrue(res != null)
        assertEquals(1, res.modifiedFiles)
    }
}
