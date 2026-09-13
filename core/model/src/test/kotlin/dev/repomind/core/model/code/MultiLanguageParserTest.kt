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

    @Test
    fun `typescript semantic parser extracts classes interfaces imports and calls`() {
        val root = Files.createTempDirectory("ts-test-repo")
        val src = root.resolve("src/services")
        Files.createDirectories(src)

        val tsFile = src.resolve("user.service.ts")
        tsFile.writeText(
            """
            import { UserRepository } from '../repositories/user.repository';
            import { IUserService } from '../interfaces/user.interface';
            import { BaseService } from './base.service';

            @Injectable()
            export class UserService extends BaseService implements IUserService {
                constructor(private readonly userRepo: UserRepository) {
                    super();
                }

                async getUser(id: string) {
                    return this.userRepo.findById(id);
                }
            }
            """.trimIndent(),
        )

        val parser = TypeScriptSemanticParser()
        val module = RepoModule(
            name = "ts-mod",
            path = root,
            buildFile = null,
            sourceRoots = listOf(SourceRoot(root.resolve("src"), isTest = false)),
        )

        val parsed = parser.parseModule(module)
        assertEquals(1, parsed.types.size)
        val userType = parsed.types.single()
        assertEquals("services.UserService", userType.fqn)
        assertTrue(userType.annotations.contains("Injectable"))
        assertTrue(userType.methods.any { it.name == "getUser" })
        assertTrue(userType.fields.any { it.name == "userRepo" })

        // Check edges
        assertTrue(parsed.edges.any { it.kind == EdgeKind.EXTENDS && it.targetFqn == "./base.service" })
        assertTrue(parsed.edges.any { it.kind == EdgeKind.IMPLEMENTS && it.targetFqn == "../interfaces/user.interface" })
        assertTrue(parsed.edges.any { it.kind == EdgeKind.USES && it.targetFqn == "../repositories/user.repository" })
        assertTrue(parsed.edges.any { it.kind == EdgeKind.CALLS && it.targetFqn == "../repositories/user.repository" })
    }

    @Test
    fun `python semantic parser extracts classes functions decorators and call edges`() {
        val root = Files.createTempDirectory("py-test-repo")
        val src = root.resolve("app/services")
        Files.createDirectories(src)

        val pyFile = src.resolve("order_service.py")
        pyFile.writeText(
            """
            from app.repositories.order_repo import OrderRepository
            from app.services.base import BaseService

            @router.service
            class OrderService(BaseService):
                def __init__(self, repo: OrderRepository):
                    self.repo = repo

                def process_order(self, order_id: str):
                    self.repo.save(order_id)
                    return True
            """.trimIndent(),
        )

        val parser = PythonSemanticParser()
        val module = RepoModule(
            name = "py-mod",
            path = root,
            buildFile = null,
            sourceRoots = listOf(SourceRoot(root.resolve("app"), isTest = false)),
        )

        val parsed = parser.parseModule(module)
        assertEquals(1, parsed.types.size)
        val orderType = parsed.types.single()
        assertEquals("services.OrderService", orderType.fqn)
        assertTrue(orderType.methods.any { it.name == "process_order" })
        assertTrue(orderType.fields.any { it.name == "repo" })

        // Check edges
        assertTrue(parsed.edges.any { it.kind == EdgeKind.EXTENDS && it.targetFqn == "app.services.base.BaseService" })
        assertTrue(parsed.edges.any { it.kind == EdgeKind.USES && it.targetFqn == "app.repositories.order_repo.OrderRepository" })
        assertTrue(parsed.edges.any { it.kind == EdgeKind.CALLS && it.targetFqn == "app.repositories.order_repo.OrderRepository" })
    }

    @Test
    fun `go semantic parser extracts packages structs interfaces receiver methods and calls`() {
        val root = Files.createTempDirectory("go-test-repo")
        val src = root.resolve("src/services")
        Files.createDirectories(src)

        val goFile = src.resolve("payment.go")
        goFile.writeText(
            """
            package services

            import (
                "github.com/sample/repo/gateway"
            )

            type PaymentProcessor interface {
                Process(amount float64) error
            }

            type PaymentService struct {
                gw *gateway.PaymentGateway
            }

            func (s *PaymentService) ExecutePayment(gw *gateway.PaymentGateway) error {
                gw.Charge()
                return nil
            }
            """.trimIndent(),
        )

        val parser = GoSemanticParser()
        val module = RepoModule(
            name = "go-mod",
            path = root,
            buildFile = null,
            sourceRoots = listOf(SourceRoot(root.resolve("src"), isTest = false)),
        )

        val parsed = parser.parseModule(module)
        assertTrue(parsed.types.any { it.fqn == "services.PaymentProcessor" && it.kind == TypeKind.INTERFACE })
        assertTrue(parsed.types.any { it.fqn == "services.PaymentService" && it.kind == TypeKind.CLASS })

        val serviceType = parsed.types.first { it.fqn == "services.PaymentService" }
        assertTrue(serviceType.methods.any { it.name == "ExecutePayment" })

        // Check edges
        assertTrue(parsed.edges.any { it.kind == EdgeKind.USES && it.targetFqn == "github.com/sample/repo/gateway.PaymentGateway" })
        assertTrue(parsed.edges.any { it.kind == EdgeKind.CALLS && it.targetFqn == "github.com/sample/repo/gateway.Charge" })
    }

    @Test
    fun `default parser registry resolves kotlin typescript python and go parsers`() {
        val registry = ParserRegistry.defaultRegistry()
        assertTrue(registry.forExtension("kt") is KotlinSemanticParser)
        assertTrue(registry.forExtension("ts") is TypeScriptSemanticParser)
        assertTrue(registry.forExtension("tsx") is TypeScriptSemanticParser)
        assertTrue(registry.forExtension("js") is TypeScriptSemanticParser)
        assertTrue(registry.forExtension("py") is PythonSemanticParser)
        assertTrue(registry.forExtension("go") is GoSemanticParser)
    }
}
