package dev.repomind.storage.sqlite

import dev.repomind.core.model.Visibility
import dev.repomind.core.model.code.ModuleParse
import dev.repomind.core.model.code.ParsedMethod
import dev.repomind.core.model.code.ParsedType
import dev.repomind.core.model.code.TypeKind
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SymbolDatabaseTest {

    private fun parseOf(types: List<ParsedType>) = ModuleParse("m1", types, emptyList())

    private fun type(fqn: String, kind: TypeKind = TypeKind.CLASS) = ParsedType(
        fqn = fqn,
        kind = kind,
        packageName = fqn.substringBeforeLast('.'),
        filePath = "/x/${fqn.substringAfterLast('.')}.java",
        lineStart = 1,
        lineEnd = 20,
        annotations = emptyList(),
        superTypeFqn = null,
        interfaceFqns = emptyList(),
        methods = listOf(
            ParsedMethod("run", "run()", Visibility.PUBLIC, isStatic = false, isAbstract = false, line = 5),
        ),
        fields = emptyList(),
    )

    @Test
    fun `indexes types and members with fqn lookups`() {
        val dbPath = Files.createTempDirectory("repomind-db").resolve("index.db")
        SymbolDatabase.open(dbPath).use { db ->
            val n = db.replaceModule("m1", parseOf(listOf(type("com.example.Service"), type("com.example.Repo", TypeKind.INTERFACE))))

            assertEquals(4, n)
            assertEquals(4L, db.count())

            val svc = db.findByFqn("com.example.Service")
            assertEquals(1, svc.size)
            assertEquals("CLASS", svc.single().kind)

            val run = db.findByFqn("com.example.Service#run()")
            assertEquals("METHOD", run.single().kind)
            assertEquals("com.example.Service", run.single().parentFqn)
        }
    }

    @Test
    fun `re-indexing a module replaces its rows without duplicates`() {
        val dbPath = Files.createTempDirectory("repomind-db").resolve("index.db")
        SymbolDatabase.open(dbPath).use { db ->
            db.replaceModule("m1", parseOf(listOf(type("com.example.A"))))
            db.replaceModule("m1", parseOf(listOf(type("com.example.A"), type("com.example.B"))))

            assertEquals(4L, db.count())
            assertTrue(db.findByFqn("com.example.A").isNotEmpty())
        }
    }

    @Test
    fun `prefix search finds symbols quickly`() {
        val dbPath = Files.createTempDirectory("repomind-db").resolve("index.db")
        SymbolDatabase.open(dbPath).use { db ->
            db.replaceModule("m1", parseOf(listOf(type("com.example.ServiceImpl"), type("com.example.Other"))))

            val hits = db.findByNamePrefix("Service")
            assertEquals(1, hits.size)
            assertEquals("com.example.ServiceImpl", hits.single().qualifiedName)
        }
    }

    @Test
    fun `fqn lookups stay under 50ms on a small index`() {
        val dbPath = Files.createTempDirectory("repomind-db").resolve("index.db")
        SymbolDatabase.open(dbPath).use { db ->
            val types = (0 until 500).map { i -> type("com.gen.Type$i") }
            db.replaceModule("gen", parseOf(types))

            db.findByFqn("com.gen.Type250")
            val start = System.nanoTime()
            repeat(100) { i -> db.findByFqn("com.gen.Type${100 + i}") }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000 / 100

            assertTrue(elapsedMs < 50, "avg lookup took ${elapsedMs}ms, budget is 50ms")
        }
    }

    @Test
    fun `read-only mode allows queries but prevents writes`() {
        val dbPath = Files.createTempDirectory("repomind-db").resolve("index.db")
        SymbolDatabase.open(dbPath).use { db ->
            db.replaceModule("m1", parseOf(listOf(type("com.example.ReadTest"))))
        }

        SymbolDatabase.open(dbPath, readOnly = true).use { roDb ->
            assertTrue(roDb.readOnly)
            val rows = roDb.findByFqn("com.example.ReadTest")
            assertEquals(1, rows.size)

            org.junit.jupiter.api.assertThrows<java.sql.SQLException> {
                roDb.recordModule("illegal", "/path")
            }
        }
    }

    @Test
    fun `concurrent write operations are safely serialized without SQLITE_BUSY`() {
        val dbPath = Files.createTempDirectory("repomind-concurrent-db").resolve("index.db")
        SymbolDatabase.open(dbPath).use { db ->
            val threads = (1..8).map { threadId ->
                Thread {
                    val types = (1..20).map { i -> type("com.thread$threadId.Class$i") }
                    db.replaceModule("mod$threadId", parseOf(types))
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            assertEquals(8 * 20 * 2L, db.count())
        }
    }
}
