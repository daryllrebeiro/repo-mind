package dev.repomind.core.impact

import dev.repomind.core.graph.InMemoryGraph
import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeprecationRadarTest {

    private fun call(from: String, to: String) = DependencyEdge(from, to, EdgeKind.CALLS, Confidence.CONFIRMED)

    @Test
    fun `detects deprecated symbols and ranks by migration effort`() {
        val edges = listOf(
            call("com.app.ConsumerA", "com.legacy.OldService#execute"),
            call("com.app.ConsumerB", "com.legacy.OldService#execute"),
            call("com.app.ConsumerC", "com.legacy.OldService#execute"),
            call("com.app.ConsumerD", "com.legacy.OldService#execute"),
            call("com.app.ConsumerE", "com.legacy.OldService#execute"),
            call("com.app.ConsumerA", "com.legacy.MinorUtil#format"),
        )
        val graph = InMemoryGraph(edges)
        val radar = DeprecationRadar(graph)

        val candidates = listOf(
            DeprecatedSymbolCandidate(
                fqn = "com.legacy.OldService#execute",
                kind = "METHOD",
                module = "core",
                annotations = listOf("Deprecated"),
                javadoc = "/** @deprecated use com.legacy.NewService#execute instead */",
            ),
            DeprecatedSymbolCandidate(
                fqn = "com.legacy.MinorUtil#format",
                kind = "METHOD",
                module = "util",
                annotations = listOf("Deprecated"),
            ),
            DeprecatedSymbolCandidate(
                fqn = "com.legacy.ActiveService#run",
                kind = "METHOD",
                module = "core",
                annotations = emptyList(), // Not deprecated
            ),
        )

        val report = radar.scan(candidates)
        assertEquals(2, report.totalDeprecatedSymbols)
        assertEquals(6, report.totalDirectCallers)
        assertTrue(report.overallDeprecationDebtScore > 0)

        val topItem = report.items.first()
        assertEquals("com.legacy.OldService#execute", topItem.fqn)
        assertEquals(5, topItem.directCallersCount)
        assertEquals("com.legacy.NewService#execute", topItem.replacementHint)

        val minorItem = report.items.last()
        assertEquals("com.legacy.MinorUtil#format", minorItem.fqn)
        assertEquals(1, minorItem.directCallersCount)
    }

    @Test
    fun `extracts replacement hints from annotations and javadoc`() {
        val radar = DeprecationRadar(InMemoryGraph(emptyList()))

        val candidate1 = DeprecatedSymbolCandidate(
            fqn = "com.test.Foo#bar",
            kind = "METHOD",
            annotations = listOf("""Deprecated(message = "Use baz instead")"""),
        )
        assertEquals("baz", radar.extractReplacementHint(candidate1))

        val candidate2 = DeprecatedSymbolCandidate(
            fqn = "com.test.Foo#qux",
            kind = "METHOD",
            annotations = listOf("Deprecated"),
            javadoc = "/** @deprecated replaced by {@link com.test.NewQux} */",
        )
        assertEquals("com.test.NewQux", radar.extractReplacementHint(candidate2))

        val candidate3 = DeprecatedSymbolCandidate(
            fqn = "com.test.Foo#ref",
            kind = "METHOD",
            annotations = listOf("Deprecated"),
            javadoc = "/** @see com.test.TargetSymbol */",
        )
        assertEquals("com.test.TargetSymbol", radar.extractReplacementHint(candidate3))
    }

    @Test
    fun `generates markdown report with debt score and migration matrix`() {
        val graph = InMemoryGraph(listOf(call("com.app.Caller", "com.legacy.OldClass")))
        val radar = DeprecationRadar(graph)

        val candidates = listOf(
            DeprecatedSymbolCandidate(
                fqn = "com.legacy.OldClass",
                kind = "CLASS",
                module = "core",
                filePath = "src/OldClass.java",
                line = 10,
                annotations = listOf("Deprecated"),
                javadoc = "/** @deprecated use com.legacy.NewClass instead */",
            ),
        )

        val report = radar.scan(candidates)
        val markdown = report.toMarkdown()

        assertTrue(markdown.contains("# RepoMind Deprecation Radar & Semantic Drift Report"))
        assertTrue(markdown.contains("Overall Deprecation Debt Score"))
        assertTrue(markdown.contains("## Migration Priority Matrix"))
        assertTrue(markdown.contains("`com.legacy.OldClass`"))
        assertTrue(markdown.contains("com.legacy.NewClass"))
        assertTrue(markdown.contains("## Migration Hotspots"))
    }
}
