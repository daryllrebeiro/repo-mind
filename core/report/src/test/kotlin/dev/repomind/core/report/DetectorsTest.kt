package dev.repomind.core.report

import dev.repomind.core.graph.InMemoryGraph
import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetectorsTest {

    private fun call(from: String, to: String, c: Confidence = Confidence.CONFIRMED) =
        DependencyEdge(from, "$to#m", EdgeKind.CALLS, c)

    private fun type(fqn: String, visibility: String = "PUBLIC", vararg annotations: String) =
        TypeInfo(fqn, "CLASS", visibility, annotations.toList())

    @Test
    fun `god class fan-out detected above threshold`() {
        val edges = (1..21).map { call("com.Busy", "com.T$it") }
        val findings = SmellDetectors(fanOutThreshold = 20).run(InMemoryGraph(edges), emptyList())

        val finding = findings.first { it.detector == "god-class-fan-out" }
        assertTrue(finding.summary.contains("21"))
        assertTrue(finding.evidence.isNotEmpty())
    }

    @Test
    fun `god class fan-in counts distinct callers`() {
        val edges = (1..16).map { call("com.Caller$it", "com.Hub") }
        val findings = SmellDetectors().run(InMemoryGraph(edges), emptyList())

        assertTrue(findings.any { it.detector == "god-class-fan-in" && it.summary.contains("16") })
    }

    @Test
    fun `dead code flags unconnected public types but spares entry points and tests`() {
        val graph = InMemoryGraph(emptyList())
        val types = listOf(
            type("com.Orphan"),
            type("com.UserController", annotations = arrayOf("Controller")),
            type("com.OrphanTest", visibility = "PUBLIC"),
        )

        val findings = SmellDetectors().run(graph, types)

        val dead = findings.first { it.detector == "dead-code" }
        assertEquals(listOf("com.Orphan"), dead.evidence)
    }

    @Test
    fun `package cycles detected via scc`() {
        val edges = listOf(
            call("a.One", "b.Two"),
            call("b.Two", "c.Three"),
            call("c.Three", "a.One"),
            call("solo.Solo", "other.Target"),
        )

        val findings = SmellDetectors().cyclicPackages(InMemoryGraph(edges))

        val cycle = findings.first { it.detector == "cyclic-packages" }
        assertEquals(setOf("a", "b", "c"), cycle.evidence.toSet())
    }

    @Test
    fun `flows start at entry points and follow calls with dashed possible hops`() {
        val edges = listOf(
            call("com.web.OrderController", "com.app.OrderService"),
            DependencyEdge("com.app.OrderService", "com.data.RepoImpl#save", EdgeKind.CALLS, Confidence.POSSIBLE),
            DependencyEdge("com.web.OrderController", "com.web.OrderControllerTest", EdgeKind.TESTS, Confidence.CONFIRMED),
        )
        val graph = InMemoryGraph(edges)
        val types = listOf(
            type("com.web.OrderController", annotations = arrayOf("RestController")),
            type("com.app.OrderService"),
            type("com.data.RepoImpl"),
            type("com.web.OrderControllerTest"),
        )

        val flows = FlowDetector().detect(graph, types)

        assertEquals(1, flows.size)
        val flow = flows.single()
        assertEquals(listOf("com.web.OrderController", "com.app.OrderService", "com.data.RepoImpl"), flow.participants)
        assertEquals(2, flow.dashedFromIndex, "POSSIBLE hop starts at index 2")
    }

    @Test
    fun `cycles do not trap flow detection`() {
        val edges = listOf(
            call("com.A", "com.B"),
            call("com.B", "com.A"),
        )
        val types = listOf(type("com.A", annotations = arrayOf("Controller")))

        val flows = FlowDetector(maxFlowsPerEntry = 1).detect(InMemoryGraph(edges), types)

        assertTrue(flows.all { flow -> flow.participants.size <= 6 })
    }
}

