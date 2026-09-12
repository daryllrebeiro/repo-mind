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

    @Test
    fun `kotlin parser extracts extension functions and receiver edges`() {
        val root = Files.createTempDirectory("kt-ext-repo")
        val src = root.resolve("src/main/kotlin/com/example")
        Files.createDirectories(src)

        val ktFile = src.resolve("OrderExtensions.kt")
        ktFile.writeText(
            """
            package com.example

            import com.example.model.Order
            import com.example.model.Price

            fun Order.calculateTotal(taxRate: Price): Double {
                return 100.0
            }
            """.trimIndent(),
        )

        val parser = KotlinSemanticParser()
        val module = RepoModule(
            name = "kt-mod",
            path = root,
            buildFile = null,
            sourceRoots = listOf(SourceRoot(root.resolve("src/main/kotlin"), isTest = false)),
        )

        val parsed = parser.parseModule(module)
        assertEquals(1, parsed.types.size)
        val fileType = parsed.types.single()
        assertEquals("com.example.OrderExtensionsKt", fileType.fqn)
        assertTrue(fileType.methods.any { it.name == "calculateTotal" })

        // Check USES edge from synthetic class to receiver type Order
        assertTrue(parsed.edges.any { it.kind == EdgeKind.USES && it.targetFqn == "com.example.model.Order" })
        // Check USES edge to param Price
        assertTrue(parsed.edges.any { it.kind == EdgeKind.USES && it.targetFqn == "com.example.model.Price" })
    }

    @Test
    fun `kotlin parser extracts companion objects and standalone objects`() {
        val root = Files.createTempDirectory("kt-obj-repo")
        val src = root.resolve("src/main/kotlin/com/example")
        Files.createDirectories(src)

        val ktFile = src.resolve("AppConfig.kt")
        ktFile.writeText(
            """
            package com.example

            object AppConfig {
                val version: String = "1.0"
            }

            class Manager {
                companion object {
                    fun create(): Manager = Manager()
                }
            }
            """.trimIndent(),
        )

        val parser = KotlinSemanticParser()
        val module = RepoModule(
            name = "kt-mod",
            path = root,
            buildFile = null,
            sourceRoots = listOf(SourceRoot(root.resolve("src/main/kotlin"), isTest = false)),
        )

        val parsed = parser.parseModule(module)
        val typeNames = parsed.types.map { it.fqn }
        assertTrue(typeNames.contains("com.example.AppConfig"))
        assertTrue(typeNames.contains("com.example.Manager"))
        assertTrue(typeNames.contains("com.example.Companion"))
    }

    @Test
    fun `kotlin parser extracts generic type arguments in constructor and properties and typealiases`() {
        val root = Files.createTempDirectory("kt-generics-repo")
        val src = root.resolve("src/main/kotlin/com/example")
        Files.createDirectories(src)

        val ktFile = src.resolve("BillingTypes.kt")
        ktFile.writeText(
            """
            package com.example

            import com.example.model.OrderItem
            import com.example.model.Customer
            import com.example.model.Invoice
            import com.example.model.PaymentGateway

            typealias PaymentMap = Map<String, PaymentGateway>

            @Service("billingService")
            class BillingService(
                val items: List<OrderItem>,
                val customers: Map<String, Customer>
            ) {
                val invoices: MutableList<Invoice> = mutableListOf()

                fun process(batch: List<Invoice>) {
                    // processing
                }
            }
            """.trimIndent(),
        )

        val parser = KotlinSemanticParser()
        val module = RepoModule(
            name = "kt-mod",
            path = root,
            buildFile = null,
            sourceRoots = listOf(SourceRoot(root.resolve("src/main/kotlin"), isTest = false)),
        )

        val parsed = parser.parseModule(module)
        val billingType = parsed.types.first { it.fqn == "com.example.BillingService" }
        assertTrue(billingType.annotations.contains("Service"))
        assertTrue(billingType.annotations.contains("@Service(\"billingService\")"))

        // Check constructor generic arguments extracted as USES edges
        assertTrue(parsed.edges.any { it.kind == EdgeKind.USES && it.targetFqn == "com.example.model.OrderItem" })
        assertTrue(parsed.edges.any { it.kind == EdgeKind.USES && it.targetFqn == "com.example.model.Customer" })

        // Check property generic argument extracted
        assertTrue(parsed.edges.any { it.kind == EdgeKind.USES && it.targetFqn == "com.example.model.Invoice" })

        // Check typealias generic argument extracted
        assertTrue(parsed.edges.any { it.kind == EdgeKind.USES && it.targetFqn == "com.example.model.PaymentGateway" })
    }
}
