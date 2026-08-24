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

class DependencyEdgeTest {

    private fun moduleWithSources(vararg sources: Pair<String, String>): RepoModule {
        val root = Files.createTempDirectory("repomind-edges")
        val src = root.resolve("src/main/java")
        for ((relPath, content) in sources) {
            val file = src.resolve(relPath)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return RepoModule(name = "m", path = root, buildFile = null, sourceRoots = listOf(SourceRoot(src, isTest = false)))
    }

    @Test
    fun `extracts extends and implements edges with confirmed confidence`() {
        val module = moduleWithSources(
            "com/example/Base.java" to "package com.example;\npublic abstract class Base {}\n",
            "com/example/Iface.java" to "package com.example;\npublic interface Iface {}\n",
            "com/example/Impl.java" to """
                package com.example;
                public class Impl extends Base implements Iface {}
            """.trimIndent(),
        )

        val result = JavaSemanticParser().parseModule(module, emptyList())
        val edges = result.edges

        val extendsEdge = edges.first { it.kind == EdgeKind.EXTENDS }
        assertEquals("com.example.Base", extendsEdge.targetFqn)
        assertEquals(Confidence.CONFIRMED, extendsEdge.confidence)

        val implEdge = edges.first { it.kind == EdgeKind.IMPLEMENTS }
        assertEquals("com.example.Iface", implEdge.targetFqn)
        assertEquals(Confidence.CONFIRMED, implEdge.confidence)
    }

    @Test
    fun `extracts import edges per type`() {
        val module = moduleWithSources(
            "C.java" to """
                import java.util.List;
                import java.util.ArrayList;

                public class C {
                    List<String> items() { return new ArrayList<>(); }
                }
            """.trimIndent(),
        )

        val edges = JavaSemanticParser().parseModule(module, emptyList()).edges
        val importTargets = edges.filter { it.kind == EdgeKind.IMPORTS }.map { it.targetFqn }.toSet()

        assertTrue("java.util.List" in importTargets)
        assertTrue("java.util.ArrayList" in importTargets)
    }

    @Test
    fun `field usage of a unique project type yields a possible uses edge`() {
        val module = moduleWithSources(
            "com/example/OrderRepo.java" to "package com.example;\npublic class OrderRepo {}\n",
            "com/example/Service.java" to """
                package com.example;
                public class Service {
                    private OrderRepo repo;
                }
            """.trimIndent(),
        )

        val edges = JavaSemanticParser().parseModule(module, emptyList()).edges
        val uses = edges.filter { it.kind == EdgeKind.USES }

        assertEquals(1, uses.size)
        assertEquals("com.example.OrderRepo", uses.single().targetFqn)
        assertEquals(Confidence.POSSIBLE, uses.single().confidence)
    }

    @Test
    fun `ambiguous simple names do not produce fabricated uses edges`() {
        val module = moduleWithSources(
            "a/Dto.java" to "package a;\npublic class Dto {}\n",
            "b/Dto.java" to "package b;\npublic class Dto {}\n",
            "c/Consumer.java" to """
                package c;
                public class Consumer {
                    private Dto data;
                }
            """.trimIndent(),
        )

        val edges = JavaSemanticParser().parseModule(module, emptyList()).edges

        assertTrue(edges.none { it.kind == EdgeKind.USES }, "ambiguous Dto must not fabricate an edge; got ${edges.filter { it.kind == EdgeKind.USES }}")
    }
}
