package dev.repomind.core.report

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MermaidEmitterTest {

    @Test
    fun `module diagram renders nodes and edges`() {
        val diagram = MermaidEmitter.moduleDiagram(
            listOf("core", "web"),
            listOf("core" to "web"),
        )

        assertTrue(diagram.startsWith("```mermaid"))
        assertTrue(diagram.contains("\"core\""))
        assertTrue(diagram.contains("-->"))
        assertTrue(diagram.trimEnd().endsWith("```"))
    }

    @Test
    fun `package diagram prunes low weight edges and caps nodes`() {
        val packages = (0 until 40).map { "com.pkg$it" }
        val edges = buildList {
            add(Triple("com.pkg0", "com.pkg1", 10))
            add(Triple("com.pkg1", "com.pkg2", 1)) // below minWeight, pruned
            for (i in 2 until 40) {
                add(Triple("com.pkg0", "com.pkg$i", 3))
            }
        }

        val diagram = MermaidEmitter.packageDiagram(packages, edges, minWeight = 2, maxNodes = 10)

        assertEquals(10, Regex("\\[\"com\\.pkg").findAll(diagram).count())
        assertTrue(diagram.contains("capped at 10"), "truncation must be disclosed")
        assertTrue(!diagram.contains("|\"1\"|"), "low-weight edge must be pruned")
    }

    @Test
    fun `flow diagram uses short labels and dashed tail when marked`() {
        val diagram = MermaidEmitter.flowDiagram(
            title = "place order",
            path = listOf("com.web.OrderController", "com.app.OrderService", "com.data.OrderRepository"),
            dashedFromIndex = 2,
        )

        assertTrue(diagram.contains("\"OrderController\""))
        assertTrue(diagram.contains("OrderController\"]:::dashed") || diagram.contains("classDef dashed"))
        assertTrue(Regex("n\\d+_[^ ]+ --> n\\d+_").containsMatchIn(diagram), diagram)
    }

    @Test
    fun `labels with quotes and brackets are sanitized`() {
        val diagram = MermaidEmitter.moduleDiagram(listOf("wei\"rd[name]"), emptyList())

        assertTrue(diagram.contains("'"), diagram)
        assertTrue(!Regex("\\[[a-z]+\"").containsMatchIn(diagram))
    }

    @Test
    fun `same label in two diagrams gets independent ids`() {
        val d1 = MermaidEmitter.moduleDiagram(listOf("m"), emptyList())
        val d2 = MermaidEmitter.moduleDiagram(listOf("m"), emptyList())

        assertEquals(d1, d2)
    }
}
