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

    @Test
    fun `transitive callers recursive CTE with depth bounds`() {
        val dbPath = Files.createTempDirectory("repomind-db").resolve("index.db")
        SymbolDatabase.open(dbPath).use { db ->
            // Chain: D -> C -> B -> A
            db.edges.replaceModule(
                "m",
                listOf(
                    DependencyEdge("com.D", "com.C", EdgeKind.CALLS, Confidence.CONFIRMED),
                    DependencyEdge("com.C", "com.B", EdgeKind.CALLS, Confidence.CONFIRMED),
                    DependencyEdge("com.B", "com.A", EdgeKind.CALLS, Confidence.CONFIRMED),
                ),
            )

            // Max depth 1 -> only B
            val callersDepth1 = db.edges.transitiveCallers("com.A", maxDepth = 1)
            assertEquals(setOf("com.B"), callersDepth1)

            // Max depth 2 -> B and C
            val callersDepth2 = db.edges.transitiveCallers("com.A", maxDepth = 2)
            assertEquals(setOf("com.B", "com.C"), callersDepth2)

            // Max depth 3 -> B, C, D
            val callersDepth3 = db.edges.transitiveCallers("com.A", maxDepth = 3)
            assertEquals(setOf("com.B", "com.C", "com.D"), callersDepth3)
        }
    }

    @Test
    fun `transitive callees recursive CTE with depth bounds`() {
        val dbPath = Files.createTempDirectory("repomind-db").resolve("index.db")
        SymbolDatabase.open(dbPath).use { db ->
            // Chain: A -> B -> C -> D
            db.edges.replaceModule(
                "m",
                listOf(
                    DependencyEdge("com.A", "com.B", EdgeKind.CALLS, Confidence.CONFIRMED),
                    DependencyEdge("com.B", "com.C", EdgeKind.CALLS, Confidence.CONFIRMED),
                    DependencyEdge("com.C", "com.D", EdgeKind.CALLS, Confidence.CONFIRMED),
                ),
            )

            val calleesDepth1 = db.edges.transitiveCallees("com.A", maxDepth = 1)
            assertEquals(setOf("com.B"), calleesDepth1)

            val calleesDepth2 = db.edges.transitiveCallees("com.A", maxDepth = 2)
            assertEquals(setOf("com.B", "com.C"), calleesDepth2)

            val calleesDepth3 = db.edges.transitiveCallees("com.A", maxDepth = 3)
            assertEquals(setOf("com.B", "com.C", "com.D"), calleesDepth3)
        }
    }

    @Test
    fun `recursive CTE handles circular dependencies without infinite loop`() {
        val dbPath = Files.createTempDirectory("repomind-db").resolve("index.db")
        SymbolDatabase.open(dbPath).use { db ->
            // Cycle: A -> B -> C -> A
            db.edges.replaceModule(
                "cycle",
                listOf(
                    DependencyEdge("com.A", "com.B", EdgeKind.CALLS, Confidence.CONFIRMED),
                    DependencyEdge("com.B", "com.C", EdgeKind.CALLS, Confidence.CONFIRMED),
                    DependencyEdge("com.C", "com.A", EdgeKind.CALLS, Confidence.CONFIRMED),
                ),
            )

            val callersOfA = db.edges.transitiveCallers("com.A", maxDepth = 10)
            assertEquals(setOf("com.B", "com.C"), callersOfA)

            val calleesOfA = db.edges.transitiveCallees("com.A", maxDepth = 10)
            assertEquals(setOf("com.B", "com.C"), calleesOfA)
        }
    }

    @Test
    fun `transitive dependents and affected tests via CTE`() {
        val dbPath = Files.createTempDirectory("repomind-db").resolve("index.db")
        SymbolDatabase.open(dbPath).use { db ->
            // Service uses Repo; Controller calls Service; Test tests Controller
            db.edges.replaceModule(
                "m",
                listOf(
                    DependencyEdge("com.Service", "com.Repo", EdgeKind.USES, Confidence.CONFIRMED),
                    DependencyEdge("com.Controller", "com.Service", EdgeKind.CALLS, Confidence.CONFIRMED),
                    DependencyEdge("com.ControllerTest", "com.Controller", EdgeKind.TESTS, Confidence.CONFIRMED),
                    DependencyEdge("com.ServiceTest", "com.Service", EdgeKind.TESTS, Confidence.CONFIRMED),
                ),
            )

            val depsOfRepo = db.edges.transitiveDependents("com.Repo")
            assertEquals(setOf("com.Service", "com.Controller"), depsOfRepo)

            val testsForRepo = db.edges.affectedTests("com.Repo")
            assertEquals(setOf("com.ServiceTest", "com.ControllerTest"), testsForRepo)
        }
    }
}
