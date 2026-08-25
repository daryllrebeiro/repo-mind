package dev.repomind.core.impact

import dev.repomind.core.graph.InMemoryGraph
import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImpactAnalyzerTest {

    private fun call(from: String, to: String) = DependencyEdge(from, to, EdgeKind.CALLS, Confidence.CONFIRMED)

    private fun meta(
        kind: String = "CLASS",
        visibility: String = "PUBLIC",
        annotations: List<String> = emptyList(),
    ) = SymbolMeta(kind, visibility, annotations)

    @Test
    fun `level boundaries map per plan`() {
        assertEquals(ImpactLevel.LOW, ImpactLevel.forScore(0))
        assertEquals(ImpactLevel.LOW, ImpactLevel.forScore(20))
        assertEquals(ImpactLevel.MEDIUM, ImpactLevel.forScore(21))
        assertEquals(ImpactLevel.MEDIUM, ImpactLevel.forScore(50))
        assertEquals(ImpactLevel.HIGH, ImpactLevel.forScore(51))
        assertEquals(ImpactLevel.HIGH, ImpactLevel.forScore(75))
        assertEquals(ImpactLevel.CRITICAL, ImpactLevel.forScore(76))
        assertEquals(ImpactLevel.CRITICAL, ImpactLevel.forScore(100))
    }

    @Test
    fun `isolated private symbol with tests scores low`() {
        val graph = InMemoryGraph(emptyList())
        val report = ImpactAnalyzer(graph).analyze("com.internal.Helper", meta(visibility = "PACKAGE"))

        assertTrue(report.signals.first { it.name == "few-tests" }.applied)
        assertEquals(10, report.score)
        assertEquals("LOW", report.level)
    }

    @Test
    fun `public hub with no tests and database annotations scores critical`() {
        val edges = (1..5).map { call("com.Caller$it", "com.Service#run") }
        val graph = InMemoryGraph(edges)

        val report = ImpactAnalyzer(graph).analyze(
            "com.Service",
            meta(kind = "CLASS", visibility = "PUBLIC", annotations = listOf("Entity")),
        )

        val appliedWeights = report.signals.filter { it.applied }.map { it.weight }
        assertEquals(setOf(30, 20, 20, 10), appliedWeights.toSet())
        assertEquals(80, report.score)
        assertEquals("CRITICAL", report.level)
        assertEquals(5, report.directCallerCount)
    }

    @Test
    fun `high fan-out contributes its weight`() {
        val edges = (1..6).map { DependencyEdge("com.Busy", "com.Dep$it#x", EdgeKind.CALLS, Confidence.CONFIRMED) }
        val graph = InMemoryGraph(edges)

        val report = ImpactAnalyzer(graph).analyze("com.Busy", meta(visibility = "PACKAGE"))

        assertTrue(report.signals.first { it.name == "high-fan-out" }.applied)
        assertEquals(25, report.score)
        assertEquals("MEDIUM", report.level)
    }

    @Test
    fun `tests covering the symbol suppress the few-tests penalty`() {
        val edges = listOf(
            call("com.A", "com.B"),
            DependencyEdge("test.BTest", "com.B", EdgeKind.TESTS, Confidence.CONFIRMED),
        )
        val graph = InMemoryGraph(edges)

        val report = ImpactAnalyzer(graph).analyze("com.B", SymbolMeta(kind = "CLASS", visibility = "PACKAGE"))

        assertFalse(report.signals.first { it.name == "few-tests" }.applied)
        assertEquals(1, report.affectedTestCount)
        assertEquals(0, report.score)
        assertEquals("LOW", report.level)
    }

    @Test
    fun `transitive callers count toward reachability but not the many-callers threshold`() {
        val graph = InMemoryGraph(listOf(call("com.Top", "com.Mid"), call("com.Mid", "com.Leaf")))

        val report = ImpactAnalyzer(graph).analyze("com.Leaf", meta(visibility = "PACKAGE"))

        assertEquals(1, report.directCallerCount)
        assertEquals(2, report.transitiveCallerCount)
        assertEquals(10, report.score)
    }

    @Test
    fun `null metadata disables public-api and db signals without crashing`() {
        val graph = InMemoryGraph(emptyList())
        val report = ImpactAnalyzer(graph).analyze("com.Unknown", null)

        assertTrue(report.signals.none { it.applied && it.name in setOf("public-api", "database-interaction") })
        assertEquals(10, report.score)
    }
}
