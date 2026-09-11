package dev.repomind.core.model.code

import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.SourceRoot
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiLanguageParserTest {

    @Test
    fun `kotlin semantic parser extracts classes functions properties and calls`() {
        val root = Files.createTempDirectory("kotlin-test-repo")
        val src = root.resolve("src/main/kotlin/com/example")
        Files.createDirectories(src)

        val ktFile = src.resolve("OrderService.kt")
        ktFile.writeText(
            """
            package com.example

            import com.example.repo.OrderRepository
            import com.example.model.Order

            @Service
            class OrderService(private val repo: OrderRepository) : IService {
                val serviceId: String = "svc-1"

                fun placeOrder(order: Order): Boolean {
                    repo.save(order)
                    return true
                }
            }
            """.trimIndent(),
        )

        val parser = KotlinSemanticParser()
        assertEquals("kotlin", parser.languageId)
        assertTrue(parser.supportedExtensions.contains("kt"))

        val module = RepoModule(
            name = "kt-mod",
            path = root,
            buildFile = null,
            sourceRoots = listOf(SourceRoot(root.resolve("src/main/kotlin"), isTest = false)),
        )

        val parsed = parser.parseModule(module)
        assertEquals(1, parsed.types.size)

        val orderType = parsed.types.single()
        assertEquals("com.example.OrderService", orderType.fqn)
        assertEquals(TypeKind.CLASS, orderType.kind)
        assertTrue(orderType.annotations.contains("Service"))
        assertTrue(orderType.methods.any { it.name == "placeOrder" })
        assertTrue(orderType.fields.any { it.name == "serviceId" })

        // Check extracted dependency edges
        assertTrue(parsed.edges.any { it.kind == EdgeKind.IMPORTS && it.targetFqn == "com.example.repo.OrderRepository" })
        assertTrue(parsed.edges.any { it.kind == EdgeKind.CALLS && it.targetFqn == "com.example.repo.OrderRepository" })
    }

    @Test
    fun `parser registry delegates to matching language parser`() {
        val registry = ParserRegistry()
        registry.register(KotlinSemanticParser())

        val ktParser = registry.forExtension("kt")
        assertTrue(ktParser is KotlinSemanticParser)

        val pyParser = registry.forExtension("py")
        assertEquals(null, pyParser)
    }
}
