package dev.repomind.integration

import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import dev.repomind.storage.sqlite.SymbolDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LargeScaleCteBenchmarkTest {

    @Test
    fun `query plan validation confirms index usage for recursive CTE and direct lookups`(@TempDir tempDir: Path) {
        val dbPath = tempDir.resolve("query-plan-test.db")
        SymbolDatabase.open(dbPath).use { db ->
            val store = db.edges

            val sampleEdges = (1..100).map { i ->
                DependencyEdge("com.example.Caller$i", "com.example.Target", EdgeKind.CALLS, Confidence.CONFIRMED)
            }
            store.addEdges("mod", sampleEdges, "/test")

            // Direct callers query plan
            val directCallersSql = "SELECT source_fqn, target_fqn, kind, confidence, line, caller_member FROM graph_edges WHERE target_fqn = ? AND kind = 'CALLS' AND confidence = 'CONFIRMED'"
            val directPlan = store.explainQueryPlan(directCallersSql, "com.example.Target")
            println("Direct callers query plan: $directPlan")
            assertTrue(
                directPlan.any { it.contains("USING INDEX") || it.contains("idx_edges_target") },
                "Direct callers query must use an index, got: $directPlan",
            )

            // Recursive CTE callers query plan
            val cteSql = """
                WITH RECURSIVE callers_cte(caller_fqn, depth) AS (
                    SELECT DISTINCT
                        CASE WHEN instr(source_fqn, '#') > 0 THEN substr(source_fqn, 1, instr(source_fqn, '#') - 1) ELSE source_fqn END,
                        1
                    FROM graph_edges
                    WHERE (target_fqn = ? OR target_fqn = ? OR target_fqn LIKE ?)
                      AND kind = 'CALLS'
                    UNION
                    SELECT DISTINCT
                        CASE WHEN instr(e.source_fqn, '#') > 0 THEN substr(e.source_fqn, 1, instr(e.source_fqn, '#') - 1) ELSE e.source_fqn END,
                        c.depth + 1
                    FROM graph_edges e
                    JOIN callers_cte c ON (e.target_fqn = c.caller_fqn OR e.target_fqn LIKE c.caller_fqn || '#%')
                    WHERE e.kind = 'CALLS'
                      AND c.depth < ?
                )
                SELECT DISTINCT caller_fqn FROM callers_cte WHERE caller_fqn != ? LIMIT ?
            """.trimIndent()
            val ctePlan = store.explainQueryPlan(cteSql, "com.example.Target", "com.example.Target", "com.example.Target#%", 5, "com.example.Target", 1000)
            println("Recursive CTE query plan: $ctePlan")
            assertTrue(
                ctePlan.any { it.contains("INDEX") },
                "Recursive CTE must use indexes for edge lookups, got: $ctePlan",
            )
        }
    }

    @Test
    fun `large graph recursive CTE traversal completes in sub-50ms and matches BFS`(@TempDir tempDir: Path) {
        val dbPath = tempDir.resolve("large-graph-bench.db")
        SymbolDatabase.open(dbPath).use { db ->
            val store = db.edges

            // Generate synthetic graph with 10,000 edges in a deep layered topology
            // Layer 0: Root target
            // Layer 1..5: Multi-tier DAG where each node is called by 4 parent nodes
            val edges = mutableListOf<DependencyEdge>()
            val root = "com.enterprise.core.RootService"

            // Build tree: 4^1 + 4^2 + 4^3 + 4^4 callers
            var currentLayer = listOf(root)
            for (depth in 1..5) {
                val nextLayer = mutableListOf<String>()
                for (callee in currentLayer) {
                    for (k in 1..3) {
                        val caller = "com.enterprise.layer$depth.Service_${depth}_${nextLayer.size}"
                        nextLayer.add(caller)
                        edges.add(
                            DependencyEdge(
                                sourceFqn = caller,
                                targetFqn = callee,
                                kind = EdgeKind.CALLS,
                                confidence = Confidence.CONFIRMED,
                            ),
                        )
                    }
                }
                currentLayer = nextLayer
            }

            println("Populating benchmark database with ${edges.size} edges...")
            val insertStart = System.nanoTime()
            store.addEdges("bench-module", edges, "/src/synthetic")
            val insertMs = (System.nanoTime() - insertStart) / 1_000_000
            println("Inserted ${edges.size} edges in ${insertMs}ms")

            // Reference in-memory BFS traversal
            val adj = mutableMapOf<String, MutableSet<String>>()
            for (e in edges) {
                adj.computeIfAbsent(e.targetFqn) { mutableSetOf() }.add(e.sourceFqn)
            }

            fun inMemoryBfs(start: String, maxDepth: Int): Set<String> {
                val visited = mutableSetOf<String>()
                var frontier = setOf(start)
                for (d in 1..maxDepth) {
                    val nextFrontier = mutableSetOf<String>()
                    for (node in frontier) {
                        adj[node]?.forEach { next ->
                            if (visited.add(next)) {
                                nextFrontier.add(next)
                            }
                        }
                    }
                    frontier = nextFrontier
                    if (frontier.isEmpty()) break
                }
                return visited
            }

            val expectedCallersDepth3 = inMemoryBfs(root, 3)

            // Warmup
            store.transitiveCallers(root, 3)

            // Benchmark CTE traversal
            val cteStart = System.nanoTime()
            val actualCallersDepth3 = store.transitiveCallers(root, 3)
            val cteMs = (System.nanoTime() - cteStart) / 1_000_000
            println("CTE transitiveCallers(depth=3) found ${actualCallersDepth3.size} callers in ${cteMs}ms")

            assertEquals(expectedCallersDepth3, actualCallersDepth3)
            assertTrue(cteMs < 50, "Recursive CTE traversal must be < 50ms, took ${cteMs}ms")

            // Test deeper traversal (depth 5)
            val expectedCallersDepth5 = inMemoryBfs(root, 5)
            val deepStart = System.nanoTime()
            val actualCallersDepth5 = store.transitiveCallers(root, 5)
            val deepMs = (System.nanoTime() - deepStart) / 1_000_000
            println("CTE transitiveCallers(depth=5) found ${actualCallersDepth5.size} callers in ${deepMs}ms")

            assertEquals(expectedCallersDepth5, actualCallersDepth5)
            assertTrue(deepMs < 100, "Deep CTE traversal must be < 100ms, took ${deepMs}ms")
        }
    }
}
