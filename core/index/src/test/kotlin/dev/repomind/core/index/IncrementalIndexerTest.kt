package dev.repomind.core.index

import dev.repomind.storage.sqlite.SymbolDatabase
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncrementalIndexerTest {

    private fun multiModuleRepo(): Path {
        val root = Files.createTempDirectory("repomind-inc")
        Files.createDirectories(root)
        root.resolve("pom.xml").writeText(
            """<project><modelVersion>4.0.0</modelVersion>
               <groupId>g</groupId><artifactId>root</artifactId><version>1</version>
               <packaging>pom</packaging>
               <modules><module>core</module><module>web</module></modules>
               </project>""".trimIndent(),
        )
        Files.createDirectories(root.resolve("core"))
        root.resolve("core/pom.xml").writeText("<project/>")
        Files.createDirectories(root.resolve("web"))
        root.resolve("web/pom.xml").writeText("<project/>")
        root.resolve("core/src/main/java/com/a/Foo.java").toFile().let { f ->
            f.parentFile.mkdirs(); f.writeText("package com.a;\npublic class Foo {\n    public int value() { return 1; }\n}\n")
        }
        root.resolve("web/src/main/java/com/b/Handler.java").toFile().let { f ->
            f.parentFile.mkdirs(); f.writeText("package com.b;\nimport com.a.Foo;\npublic class Handler {\n    public int go() { return new Foo().value(); }\n}\n")
        }
        return root
    }

    @Test
    fun `first run is a full index`() {
        val root = multiModuleRepo()
        val result = IncrementalIndexer(root.resolve(".repomind/index.db")).update(root)

        assertTrue(result.fullIndex)
        assertEquals(2, result.addedFiles)
        assertEquals(2, result.reparsedModules.size)
        assertTrue(result.elapsedMs >= 0)
    }

    @Test
    fun `second run with no changes is a no-op`() {
        val root = multiModuleRepo()
        val indexer = IncrementalIndexer(root.resolve(".repomind/index.db"))
        indexer.update(root)

        val result = indexer.update(root)

        assertEquals(0, result.addedFiles)
        assertEquals(0, result.modifiedFiles)
        assertEquals(0, result.reparsedModules.size)
        assertEquals(3, result.skippedModules)
    }

    @Test
    fun `modifying shared type invalidates dependent module too`() {
        val root = multiModuleRepo()
        val indexer = IncrementalIndexer(root.resolve(".repomind/index.db"))
        indexer.update(root)

        root.resolve("core/src/main/java/com/a/Foo.java").writeText(
            "package com.a;\npublic class Foo {\n    public int value() { return 42; }\n}\n",
        )
        val result = indexer.update(root)

        assertEquals(1, result.modifiedFiles)
        assertEquals(listOf("core", "web"), result.reparsedModules, "dependent module must be invalidated")
        assertEquals(1, result.skippedModules)
    }

    @Test
    fun `incremental result matches fresh full index as ground truth`() {
        val root = multiModuleRepo()
        val dbPath = root.resolve(".repomind/index.db")
        val indexer = IncrementalIndexer(dbPath)
        val full = indexer.update(root)

        root.resolve("core/src/main/java/com/a/Foo.java").writeText(
            "package com.a;\npublic class Foo {\n    public int value() { return 7; }\n    public String name() { return \"x\"; }\n}\n",
        )
        indexer.update(root)

        SymbolDatabase.open(dbPath).use { incrementalDb ->
            val freshRoot = multiModuleRepo()
            // rebuild same content as modified version for ground truth comparison
            freshRoot.resolve("core/src/main/java/com/a/Foo.java").writeText(
                "package com.a;\npublic class Foo {\n    public int value() { return 7; }\n    public String name() { return \"x\"; }\n}\n",
            )
            SymbolDatabase.open(freshRoot.resolve(".repomind/index.db")).use { freshDb ->
                val fresh = IncrementalIndexer(freshRoot.resolve(".repomind/index.db")).update(freshRoot)
                assertEquals(fresh.symbolsIndexed, incrementalDb.count().toInt())
                assertEquals(fresh.edgesIndexed, incrementalDb.edges.count().toInt())
                assertEquals(
                    incrementalDb.findByFqn("com.a.Foo#name()").isNotEmpty(),
                    true,
                )
            }
            check(full.fullIndex)
        }
    }

    @Test
    fun `deleted files drop their symbols on next update`() {
        val root = multiModuleRepo()
        val indexer = IncrementalIndexer(root.resolve(".repomind/index.db"))
        indexer.update(root)

        Files.delete(root.resolve("core/src/main/java/com/a/Foo.java"))
        val result = indexer.update(root)

        assertEquals(1, result.deletedFiles)
        SymbolDatabase.open(root.resolve(".repomind/index.db")).use { db ->
            assertEquals(emptyList(), db.findByFqn("com.a.Foo"))
            assertTrue(db.fileStates("core").isEmpty(), "deleted module files must be reflected in state")
        }
    }

    @Test
    fun `single-file change reindexes in under one second on fixture scale`() {
        val root = Files.createTempDirectory("repomind-perf")
        Files.createDirectories(root)
        root.resolve("pom.xml").writeText(
            """<project><modelVersion>4.0.0</modelVersion>
               <groupId>g</groupId><artifactId>root</artifactId><version>1</version>
               <packaging>pom</packaging>
               <modules><module>app</module></modules>
               </project>""".trimIndent(),
        )
        Files.createDirectories(root.resolve("app"))
        root.resolve("app/pom.xml").writeText("<project/>")
        for (i in 1..120) {
            root.resolve("app/src/main/java/gen/T$i.java").toFile().let { f ->
                f.parentFile.mkdirs()
                f.writeText("package gen;\npublic class T$i { public int v$i() { return $i; } }\n")
            }
        }
        val indexer = IncrementalIndexer(root.resolve(".repomind/index.db"))
        indexer.update(root)
        indexer.update(root)

        root.resolve("app/src/main/java/gen/T1.java").writeText("package gen;\npublic class T1 { public int v1() { return 111; } }\n")
        val start = System.nanoTime()
        val result = indexer.update(root)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(listOf("app"), result.reparsedModules)
        println("incremental-update=${elapsedMs}ms")
        assertTrue(elapsedMs < 1000, "incremental update took ${elapsedMs}ms; budget is 1000ms")
    }
}


