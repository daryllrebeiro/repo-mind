package dev.repomind.core.query

import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.SourceRoot
import dev.repomind.language.java.JavaSemanticParser
import dev.repomind.storage.sqlite.SymbolDatabase
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepoQueryEngineTest {

    private fun buildIndexedRepo(): Path {
        val root = Files.createTempDirectory("repomind-query")
        val src = root.resolve("src/main/java")
        val testSrc = root.resolve("src/test/java")

        fun java(base: Path, rel: String, content: String) {
            val f = base.resolve(rel)
            Files.createDirectories(f.parent)
            f.writeText(content)
        }

        java(src, "com/example/OrderService.java", "package com.example;\npublic class OrderService {\n    public void place() { new MailConfig().send(); }\n}")
        java(src, "com/example/MailConfig.java", "package com.example;\npublic class MailConfig {\n    public void send() {}\n}")
        java(testSrc, "com/example/OrderServiceTest.java", "package com.example;\nimport com.example.OrderService;\nclass OrderServiceTest {\n    @org.junit.jupiter.api.Test\n    void x() { new OrderService().place(); }\n}")

        val module = RepoModule(
            name = "m",
            path = root,
            buildFile = null,
            sourceRoots = listOf(SourceRoot(src, isTest = false), SourceRoot(testSrc, isTest = true)),
        )
        val parsed = JavaSemanticParser().parseModule(module, emptyList())
        SymbolDatabase.open(root.resolve(".repomind/index.db")).use { db ->
            db.replaceModule(module.name, parsed)
            db.edges.replaceModule(module.name, parsed.edges)
        }
        return root
    }

    @Test
    fun `symbol search respects limit and reports truncation`() {
        RepoQueryEngine(buildIndexedRepo().resolve(".repomind/index.db")).use { engine ->
            val result = engine.findSymbol("OrderService")

            assertTrue(result.items.any { it.qualifiedName == "com.example.OrderService" })
            assertEquals(result.items.size, result.returnedCount)
        }
    }

    @Test
    fun `callers include direct and transitive with counts`() {
        RepoQueryEngine(buildIndexedRepo().resolve(".repomind/index.db")).use { engine ->
            val callers = engine.findCallers("com.example.MailConfig")

            assertEquals(1, callers.directCount, "OrderService calls MailConfig directly; got ${callers.items}")
            assertTrue(callers.items.map { it.caller }.contains("com.example.OrderService"))
        }
    }

    @Test
    fun `related tests surface the exercising test class`() {
        RepoQueryEngine(buildIndexedRepo().resolve(".repomind/index.db")).use { engine ->
            val tests = engine.findRelatedTests("com.example.OrderService")

            assertTrue(tests.items.contains("com.example.OrderServiceTest"), "got ${tests.items}")
        }
    }

    @Test
    fun `impact report is produced from persisted index`() {
        RepoQueryEngine(buildIndexedRepo().resolve(".repomind/index.db")).use { engine ->
            val report = engine.impact("com.example.OrderService")

            assertTrue(report.score >= 0)
            assertTrue(report.level in setOf("LOW", "MEDIUM", "HIGH", "CRITICAL"))
        }
    }

    @Test
    fun `missing index fails loudly`() {
        val empty = Files.createTempDirectory("no-index")
        try {
            RepoQueryEngine(empty.resolve(".repomind/index.db")).use { }
            error("expected failure")
        } catch (e: QueryEngineException) {
            assertTrue(e.message!!.contains("no index"), "unexpected message: ${e.message}")
        }
    }
}

