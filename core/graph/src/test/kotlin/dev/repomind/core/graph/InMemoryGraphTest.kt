package dev.repomind.core.graph

import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryGraphTest {

    private fun call(from: String, to: String) = DependencyEdge(from, to, EdgeKind.CALLS, Confidence.CONFIRMED)

    @Test
    fun `transitive callers traverse the full reverse chain`() {
        val graph = InMemoryGraph(
            listOf(
                call("com.C", "com.B"),
                call("com.B", "com.A"),
                call("com.X", "com.Y"),
            ),
        )

        assertEquals(setOf("com.B", "com.C"), graph.transitiveCallers("com.A"))
    }

    @Test
    fun `dependents include structural edges not just calls`() {
        val graph = InMemoryGraph(
            listOf(
                DependencyEdge("com.ServiceImpl", "com.Service", EdgeKind.IMPLEMENTS, Confidence.CONFIRMED),
                call("com.Controller", "com.ServiceImpl#handle"),
            ),
        )

        assertEquals(setOf("com.ServiceImpl", "com.Controller"), graph.transitiveDependents("com.Service"))
    }

    @Test
    fun `cycles terminate and every participant is reported`() {
        val graph = InMemoryGraph(
            listOf(
                call("com.A", "com.B"),
                call("com.B", "com.A"),
                call("com.C", "com.A"),
            ),
        )

        val callers = graph.transitiveCallers("com.A")

        assertEquals(setOf("com.B", "com.C"), callers)
    }

    @Test
    fun `affected tests reach tests of transitive dependents`() {
        val edges = mutableListOf<DependencyEdge>()
        edges += call("com.Controller", "com.Service")
        edges += DependencyEdge("test.ServiceTest", "com.Service", EdgeKind.TESTS, Confidence.CONFIRMED)
        edges += DependencyEdge("test.ControllerTest", "com.Controller", EdgeKind.TESTS, Confidence.POSSIBLE)
        edges += DependencyEdge("test.UnrelatedTest", "com.Unrelated", EdgeKind.TESTS, Confidence.CONFIRMED)
        val graph = InMemoryGraph(edges)

        assertEquals(setOf("test.ServiceTest", "test.ControllerTest"), graph.affectedTests("com.Service"))
    }

    @Test
    fun `maxDepth bounds traversal`() {
        val graph = InMemoryGraph(listOf(call("a3", "a2"), call("a2", "a1")))

        assertEquals(emptySet(), graph.transitiveCallers("a1", maxDepth = 0))
        assertTrue(graph.transitiveCallers("a1").containsAll(setOf("a2", "a3")))
    }

    @Test
    fun `hub traversal on 100k nodes stays under 200ms`() {
        val nodes = (0 until 100_000).map { "com.gen.T$it" }
        val edges = mutableListOf<DependencyEdge>()

        for (i in 1 until 50_000) {
            edges += call(nodes[i], nodes[(i - 1) / 2])
        }
        for (i in 50_000 until 100_000) {
            edges += call(nodes[i], nodes[i % 500])
        }

        val startHydration = System.nanoTime()
        val graph = InMemoryGraph(edges)
        val hydrationMs = (System.nanoTime() - startHydration) / 1_000_000

        val warmup = graph.transitiveCallers(nodes[0])
        val start = System.nanoTime()
        repeat(20) { graph.transitiveCallers(nodes[0]) }
        val avgMs = (System.nanoTime() - start) / 1_000_000 / 20

        assertTrue(warmup.size > 40_000, "expected large reachable set from hub, got ${warmup.size}")
        println("hydration=${hydrationMs}ms hub-traversal-avg=${avgMs}ms reachable=${warmup.size}")
        assertTrue(avgMs < 200, "hub traversal averaged ${avgMs}ms; budget is 200ms")
    }
}
