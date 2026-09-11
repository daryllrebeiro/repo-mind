package dev.repomind.language.java

import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.SourceRoot
import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.EdgeKind
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpringCallGraphTest {

    private fun moduleWithSources(
        mainSources: Map<String, String>,
        testSources: Map<String, String> = emptyMap(),
    ): RepoModule {
        val root = Files.createTempDirectory("repomind-spring-callgraph")
        val mainSrc = root.resolve("src/main/java")
        val testSrc = root.resolve("src/test/java")

        for ((relPath, content) in mainSources) {
            val file = mainSrc.resolve(relPath)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        for ((relPath, content) in testSources) {
            val file = testSrc.resolve(relPath)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }

        return RepoModule(
            name = "spring-sample",
            path = root,
            buildFile = null,
            sourceRoots = listOf(
                SourceRoot(mainSrc, isTest = false),
                SourceRoot(testSrc, isTest = true),
            ),
        )
    }

    @Test
    fun `spring qualifier on field disambiguates interface call to concrete bean with confirmed confidence`() {
        val module = moduleWithSources(
            mainSources = mapOf(
                "com/example/PaymentService.java" to """
                    package com.example;
                    public interface PaymentService {
                        void pay(int amount);
                    }
                """.trimIndent(),
                "com/example/StripePaymentService.java" to """
                    package com.example;
                    public class StripePaymentService implements PaymentService {
                        public void pay(int amount) {}
                    }
                """.trimIndent(),
                "com/example/PaypalPaymentService.java" to """
                    package com.example;
                    public class PaypalPaymentService implements PaymentService {
                        public void pay(int amount) {}
                    }
                """.trimIndent(),
                "com/example/CheckoutController.java" to """
                    package com.example;
                    import org.springframework.beans.factory.annotation.Autowired;
                    import org.springframework.beans.factory.annotation.Qualifier;

                    public class CheckoutController {
                        @Autowired
                        @Qualifier("stripePaymentService")
                        private PaymentService paymentService;

                        public void checkout() {
                            paymentService.pay(100);
                        }
                    }
                """.trimIndent(),
            ),
        )

        val parse = JavaSemanticParser().parseModule(module, emptyList())
        val callEdges = parse.edges.filter { it.kind == EdgeKind.CALLS && it.sourceFqn == "com.example.CheckoutController" }

        val toStripe = callEdges.firstOrNull { "StripePaymentService#pay" in it.targetFqn }
        assertNotNull(toStripe, "Expected confirmed call edge to StripePaymentService#pay; got $callEdges")
        assertEquals(Confidence.CONFIRMED, toStripe.confidence)
        assertEquals("checkout", toStripe.callerMember)

        // PayPal should NOT be targeted because @Qualifier disambiguated to Stripe
        val toPaypal = callEdges.firstOrNull { "PaypalPaymentService#pay" in it.targetFqn }
        assertEquals(null, toPaypal, "PaypalPaymentService must not be called when Stripe is qualified; got $toPaypal")
    }

    @Test
    fun `constructor injection with qualifier disambiguates multiple implementations`() {
        val module = moduleWithSources(
            mainSources = mapOf(
                "com/example/NotificationSender.java" to """
                    package com.example;
                    public interface NotificationSender {
                        void send(String msg);
                    }
                """.trimIndent(),
                "com/example/EmailSender.java" to """
                    package com.example;
                    public class EmailSender implements NotificationSender {
                        public void send(String msg) {}
                    }
                """.trimIndent(),
                "com/example/SmsSender.java" to """
                    package com.example;
                    public class SmsSender implements NotificationSender {
                        public void send(String msg) {}
                    }
                """.trimIndent(),
                "com/example/AlertManager.java" to """
                    package com.example;
                    import org.springframework.beans.factory.annotation.Qualifier;

                    public class AlertManager {
                        private final NotificationSender sender;

                        public AlertManager(@Qualifier("emailSender") NotificationSender sender) {
                            this.sender = sender;
                        }

                        public void triggerAlert() {
                            sender.send("Warning!");
                        }
                    }
                """.trimIndent(),
            ),
        )

        val parse = JavaSemanticParser().parseModule(module, emptyList())
        val callEdges = parse.edges.filter { it.kind == EdgeKind.CALLS && it.sourceFqn == "com.example.AlertManager" }

        val toEmail = callEdges.firstOrNull { "EmailSender#send" in it.targetFqn }
        assertNotNull(toEmail, "Expected call to EmailSender#send; got $callEdges")
        assertEquals(Confidence.CONFIRMED, toEmail.confidence)
        assertEquals("triggerAlert", toEmail.callerMember)

        val toSms = callEdges.firstOrNull { "SmsSender#send" in it.targetFqn }
        assertEquals(null, toSms, "SmsSender should not be targeted")
    }

    @Test
    fun `mockbean in test class excludes mocked dependency from test coverage mapping`() {
        val module = moduleWithSources(
            mainSources = mapOf(
                "com/example/BillingService.java" to """
                    package com.example;
                    public class BillingService {
                        public void bill() {}
                    }
                """.trimIndent(),
                "com/example/OrderService.java" to """
                    package com.example;
                    public class OrderService {
                        public void placeOrder() {}
                    }
                """.trimIndent(),
            ),
            testSources = mapOf(
                "com/example/OrderServiceTest.java" to """
                    package com.example;
                    import org.junit.jupiter.api.Test;
                    import org.springframework.boot.test.mock.mockito.MockBean;

                    public class OrderServiceTest {
                        @MockBean
                        private BillingService billingService;

                        private OrderService orderService = new OrderService();

                        @Test
                        public void testPlaceOrder() {
                            orderService.placeOrder();
                            billingService.bill();
                        }
                    }
                """.trimIndent(),
            ),
        )

        val parse = JavaSemanticParser().parseModule(module, emptyList())
        val testEdges = parse.edges.filter { it.kind == EdgeKind.TESTS && it.sourceFqn == "com.example.OrderServiceTest" }

        // OrderService is the real class under test
        val coversOrderService = testEdges.any { it.targetFqn == "com.example.OrderService" }
        assertTrue(coversOrderService, "OrderServiceTest should cover OrderService; got $testEdges")

        // BillingService is @MockBean, so it MUST NOT have test coverage edge
        val coversBillingService = testEdges.any { it.targetFqn == "com.example.BillingService" }
        assertFalse(coversBillingService, "Mocked BillingService must be excluded from test coverage; got $testEdges")
    }

    @Test
    fun `reflection invocation produces calls edge flagged with possible confidence`() {
        val module = moduleWithSources(
            mainSources = mapOf(
                "com/example/Reflector.java" to """
                    package com.example;
                    import java.lang.reflect.Method;

                    public class Reflector {
                        public void invokeDynamically(Object target, Method m) throws Exception {
                            m.invoke(target);
                            Class.forName("com.example.Target");
                        }
                    }
                """.trimIndent(),
            ),
        )

        val parse = JavaSemanticParser().parseModule(module, emptyList())
        val callEdges = parse.edges.filter { it.kind == EdgeKind.CALLS && it.sourceFqn == "com.example.Reflector" }

        val invokeEdge = callEdges.firstOrNull { "Method#invoke" in it.targetFqn }
        assertNotNull(invokeEdge, "Expected reflection Method#invoke edge; got $callEdges")
        assertEquals(Confidence.POSSIBLE, invokeEdge.confidence)
        assertEquals("invokeDynamically", invokeEdge.callerMember)

        val forNameEdge = callEdges.firstOrNull { "Class#forName" in it.targetFqn }
        assertNotNull(forNameEdge, "Expected Class#forName edge; got $callEdges")
        assertEquals(Confidence.POSSIBLE, forNameEdge.confidence)
    }
}
