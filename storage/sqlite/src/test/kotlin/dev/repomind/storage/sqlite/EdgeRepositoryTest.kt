package dev.repomind.storage.sqlite

import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EdgeRepositoryTest {

    @Test
    fun `persists and queries edges by source target and kind`() {
        val dbPath = Files.createTempDirectory("repomind-db").resolve("index.db")
        SymbolDatabase.open(dbPath).use { db ->
            db.edges.replaceModule(
                "m1",
                listOf(
                    DependencyEdge("com.A", "com.B", EdgeKind.EXTENDS, Confidence.CONFIRMED),
                    DependencyEdge("com.C", "com.B", EdgeKind.USES, Confidence.POSSIBLE),
                    DependencyEdge("com.C", "java.util.List", EdgeKind.IMPORTS, Confidence.CONFIRMED),
                ),
            )

            assertEquals(3L, db.edges.count())
            assertEquals(1, db.edges.findBySource("com.A").size)
            assertEquals(2, db.edges.findByTarget("com.B").size)
            assertTrue(db.edges.findByKind(EdgeKind.USES).all { it.confidence == "POSSIBLE" })
        }
    }

    @Test
    fun `replacing a module removes only its edges`() {
        val dbPath = Files.createTempDirectory("repomind-db").resolve("index.db")
        SymbolDatabase.open(dbPath).use { db ->
            db.edges.replaceModule("m1", listOf(DependencyEdge("a.X", "a.Y", EdgeKind.USES, Confidence.POSSIBLE)))
            db.edges.replaceModule("m2", listOf(DependencyEdge("b.P", "b.Q", EdgeKind.EXTENDS, Confidence.CONFIRMED)))

            db.edges.replaceModule("m1", listOf(DependencyEdge("a.Z", "a.W", EdgeKind.IMPORTS, Confidence.CONFIRMED)))

            assertEquals(2L, db.edges.count())
            assertTrue(db.edges.findBySource("a.X").isEmpty())
            assertTrue(db.edges.findBySource("b.P").isNotEmpty())
        }
    }
}
