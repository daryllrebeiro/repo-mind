package dev.repomind.language.java

import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.SourceRoot
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaSemanticParserTest {

    private fun moduleWithSources(vararg sources: Pair<String, String>): RepoModule {
        val root = Files.createTempDirectory("repomind-parse")
        val src = root.resolve("src/main/java")
        for ((relPath, content) in sources) {
            val file = src.resolve(relPath)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return RepoModule(
            name = "test-module",
            path = root,
            buildFile = null,
            sourceRoots = listOf(SourceRoot(src, isTest = false)),
        )
    }

    @Test
    fun `extracts types with fqns methods and fields`() {
        val module = moduleWithSources(
            "com/example/OrderService.java" to """
                package com.example;
                public class OrderService {
                    private final OrderRepository repository;
                    public Order place(Order order) { return null; }
                    static int helper() { return 1; }
                }
            """.trimIndent(),
        )
        val result = JavaSemanticParser().parseModule(module, emptyList())

        assertEquals(1, result.types.size)
        val type = result.types.single()
        assertEquals("com.example.OrderService", type.fqn)
        assertEquals(dev.repomind.core.model.code.TypeKind.CLASS, type.kind)
        assertEquals(2, type.methods.size)
        assertEquals(listOf("place(Order)", "helper()"), type.methods.map { it.signature })
        assertEquals(1, type.fields.size)
    }

    @Test
    fun `resolves same-project types via source roots`() {
        val module = moduleWithSources(
            "com/example/Repo.java" to """
                package com.example;
                public interface Repo {}
            """.trimIndent(),
            "com/example/Impl.java" to """
                package com.example;
                public class Impl implements Repo {}
            """.trimIndent(),
        )
        val result = JavaSemanticParser().parseModule(module, emptyList())

        val impl = result.types.first { it.fqn == "com.example.Impl" }
        assertNull(result.unresolvedSymbols.firstOrNull())
        assertEquals(listOf("com.example.Repo"), impl.interfaceFqns)
    }

    @Test
    fun `resolves cross-package project types through imports`() {
        val module = moduleWithSources(
            "com/example/core/Base.java" to """
                package com.example.core;
                public abstract class Base {}
            """.trimIndent(),
            "com/example/web/Page.java" to """
                package com.example.web;
                import com.example.core.Base;
                public class Page extends Base {}
            """.trimIndent(),
        )
        val result = JavaSemanticParser().parseModule(module, emptyList())

        val page = result.types.first { it.fqn == "com.example.web.Page" }
        assertEquals("com.example.core.Base", page.superTypeFqn)
        assertTrue(result.unresolvedSymbols.isEmpty(), "unresolved: ${result.unresolvedSymbols}")
    }

    @Test
    fun `resolves jdk types without any classpath jars`() {
        val module = moduleWithSources(
            "A.java" to """
                import java.util.List;
                public interface A { List<String> names(); }
            """.trimIndent(),
        )
        val result = JavaSemanticParser().parseModule(module, emptyList())

        assertNotNull(result.types.single())
        assertTrue(result.unresolvedSymbols.isEmpty())
    }

    @Test
    fun `records unresolved symbols instead of failing silently`() {
        val module = moduleWithSources(
            "B.java" to """
                import does.not.Exist;
                public class B extends Exist {}
            """.trimIndent(),
        )
        val result = JavaSemanticParser().parseModule(module, emptyList())

        assertTrue(result.unresolvedSymbols.isNotEmpty(), "expected at least one unresolved symbol")
        assertTrue(result.unresolvedSymbols.any { it.symbol.contains("Exist") })
    }

    @Test
    fun `synthesizes lombok getters setters and logger field`() {
        val module = moduleWithSources(
            "Dto.java" to """
                import lombok.Builder;
                import lombok.Data;
                import lombok.extern.slf4j.Slf4j;

                @Data
                @Builder
                @Slf4j
                public class Dto {
                    private final String name;
                    private int count;
                }
            """.trimIndent(),
        )
        val result = JavaSemanticParser().parseModule(module, emptyList())

        val dto = result.types.single()
        val syntheticNames = dto.methods.filter { it.synthetic }.map { it.name }.toSet()
        for (expected in setOf("getName", "getCount", "setCount", "equals", "hashCode", "toString", "builder")) {
            assertTrue(expected in syntheticNames, "missing synthetic member $expected; had $syntheticNames")
        }
        assertTrue(dto.fields.any { it.synthetic && it.name == "log" })
    }
}
