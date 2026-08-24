package dev.repomind.core.eval

import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import org.junit.jupiter.api.Test
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import java.nio.file.Files
import kotlin.test.assertTrue

class EvalHarnessTest {

    private fun call(source: String, target: String, confidence: Confidence = Confidence.POSSIBLE) =
        DependencyEdge(source, target, EdgeKind.CALLS, confidence)

    @Test
    fun `perfect extraction scores one`() {
        val edges = listOf(
            call("com.A", "com.B#run", Confidence.CONFIRMED),
        )
        val cases = listOf(
            EvalCase("direct-call", expectations = listOf(EdgeExpectation("com.A", "com.B", minConfidence = "CONFIRMED"))),
        )

        val report = EvalHarness().run(edges, cases)

        assertEquals(1.0, report.macroPrecision)
        assertEquals(1.0, report.macroRecall)
    }

    @Test
    fun `confirmed expectation rejects possible edges`() {
        val edges = listOf(call("com.A", "com.B#run", Confidence.POSSIBLE))
        val cases = listOf(
            EvalCase(
                "strict",
                expectations = listOf(EdgeExpectation("com.A", "com.B", minConfidence = "CONFIRMED")),
            ),
        )

        val result = EvalHarness().run(edges, cases).caseResults.single()

        assertEquals(0, result.matched)
        assertEquals(1, result.missed)
        assertTrue(result.missedDetails.single().contains("com.B"))
    }

    @Test
    fun `possible expectation accepts confirmed edges`() {
        val edges = listOf(call("com.A", "com.B#run", Confidence.CONFIRMED))
        val cases = listOf(EvalCase("loose", expectations = listOf(EdgeExpectation("com.A", "com.B"))))

        val result = EvalHarness().run(edges, cases).caseResults.single()

        assertEquals(1, result.matched)
        assertEquals(0.0, result.spurious.toDouble())
    }

    @Test
    fun `target owner matching ignores method suffix`() {
        val edges = listOf(call("svc.Service", "repo.RepoImpl#save"))
        val cases = listOf(EvalCase("impl-attributed", expectations = listOf(EdgeExpectation("svc.Service", "repo.RepoImpl"))))

        val result = EvalHarness().run(edges, cases).caseResults.single()

        assertEquals(1, result.matched)
    }

    @Test
    fun `spurious calls from a case's sources reduce precision`() {
        val edges = listOf(
            call("com.A", "com.B#run"),
            call("com.A", "com.Unexpected#boom"),
        )
        val cases = listOf(EvalCase("noisy", expectations = listOf(EdgeExpectation("com.A", "com.B"))))

        val result = EvalHarness().run(edges, cases).caseResults.single()

        assertEquals(1, result.matched)
        assertEquals(1, result.spurious)
        assertEquals(0.5, result.precision)
    }

    @Test
    fun `empty expectations yield perfect vacuous case`() {
        val report = EvalHarness().run(emptyList(), listOf(EvalCase("vacuous", expectations = emptyList())))

        assertEquals(1.0, report.macroRecall)
        assertTrue(report.caseResults.single().isPerfect())
    }
}
