package dev.repomind.language.java

import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.SourceRoot
import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.EdgeKind
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestGraphTest {

    private fun moduleWithSources(sources: Map<String, String>): RepoModule {
        val root = Files.createTempDirectory("repomind-tests")
        val src = root.resolve("src/main/java")
        val testSrc = root.resolve("src/test/java")
        for ((relPath, content) in sources) {
            val isTest = relPath.startsWith("test/")
            val base = if (isTest) testSrc else src
            val rel = relPath.removePrefix("test/")
            val file = base.resolve(rel)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return RepoModule(
            name = "m",
            path = root,
            buildFile = null,
            sourceRoots = listOf(SourceRoot(src, isTest = false), SourceRoot(testSrc, isTest = true)),
        )
    }

    @Test
    fun `explicit import of production type yields confirmed tests edge`() {
        val module = moduleWithSources(
            mapOf(
                "com/example/Calculator.java" to """
                    package com.example;
                    public class Calculator { public int add(int a, int b) { return a + b; } }
                """.trimIndent(),
                "test/com/example/CalculatorTest.java" to """
                    package com.example;
                    import org.junit.jupiter.api.Test;
                    import com.example.Calculator;

                    class CalculatorTest {
                        @org.junit.jupiter.api.Test
                        void adds() { new Calculator().add(1, 2); }
                    }
                """.trimIndent(),
            ),
        )

        val parsed = JavaSemanticParser().parseModule(module, emptyList())
        assertTrue(parsed.types.any { it.isTest && it.fqn == "com.example.CalculatorTest" })

        val testEdges = parsed.edges.filter { it.kind == EdgeKind.TESTS }
        assertEquals(1, testEdges.size)
        assertEquals("com.example.Calculator", testEdges.single().targetFqn)
        assertEquals(Confidence.CONFIRMED, testEdges.single().confidence)
    }

    @Test
    fun `same-package test without import gets possible edge via unique name match`() {
        val module = moduleWithSources(
            mapOf(
                "com/example/Greeter.java" to "package com.example;\npublic class Greeter {}\n",
                "test/com/example/GreeterTest.java" to "package com.example;\nclass GreeterTest {\n    private com.example.Greeter greeter;\n}\n",
            ),
        )

        val edges = JavaSemanticParser().parseModule(module, emptyList()).edges
            .filter { it.kind == EdgeKind.TESTS }

        assertEquals(1, edges.size)
        assertEquals(Confidence.POSSIBLE, edges.single().confidence)
    }

    @Test
    fun `production types never get tests edges between themselves`() {
        val module = moduleWithSources(
            mapOf(
                "com/example/A.java" to "package com.example;\npublic class A {}\n",
                "com/example/ATestLike.java" to "package com.example;\npublic class ATestLike { private A a; }\n",
            ),
        )

        val edges = JavaSemanticParser().parseModule(module, emptyList()).edges
            .filter { it.kind == EdgeKind.TESTS }

        assertTrue(edges.isEmpty(), "no test sources present; got $edges")
    }

    @Test
    fun `confirmed and possible do not duplicate for same target`() {
        val module = moduleWithSources(
            mapOf(
                "com/example/Calc.java" to "package com.example;\npublic class Calc {}\n",
                "test/com/example/CalcTest.java" to "package com.example;\nimport com.example.Calc;\nclass CalcTest {\n    private Calc calc;\n}\n",
            ),
        )

        val edges = JavaSemanticParser().parseModule(module, emptyList()).edges
            .filter { it.kind == EdgeKind.TESTS }

        assertEquals(1, edges.size)
        assertEquals(Confidence.CONFIRMED, edges.single().confidence)
    }
}
