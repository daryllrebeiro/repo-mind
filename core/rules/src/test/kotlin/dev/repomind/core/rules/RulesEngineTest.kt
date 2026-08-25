package dev.repomind.core.rules

import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulesEngineTest {

    private fun type(fqn: String, vararg annotations: String) =
        TypeStereotypeInfo(fqn, annotations.toList())

    private val yaml = """
        rules:
          - name: controllers-cannot-access-repositories
            description: Controllers must go through services
            from:
              annotations: [RestController, Controller]
            to:
              annotations: [Repository]
            message: Use a service layer between controllers and repositories
          - name: services-are-not-managers
            from:
              namePattern: ".*Service"
            to:
              namePattern: ".*Manager"
    """.trimIndent()

    @Test
    fun `parses yaml rule definitions`() {
        val rules = RuleLoader.parse(yaml)

        assertEquals(2, rules.size)
        val first = rules[0]
        assertEquals("controllers-cannot-access-repositories", first.name)
        assertEquals(listOf("RestController", "Controller"), first.from.annotations)
        assertEquals(listOf("CALLS", "USES"), first.edgeKinds)
    }

    @Test
    fun `controller calling repository produces violation`() {
        val types = listOf(
            type("com.web.UserController", "RestController"),
            type("com.data.UserRepository", "Repository"),
            type("com.service.UserService"),
        )
        val edges = listOf(
            DependencyEdge("com.web.UserController", "com.data.UserRepository#findById", EdgeKind.CALLS, Confidence.CONFIRMED),
            DependencyEdge("com.web.UserController", "com.service.UserService#find", EdgeKind.CALLS, Confidence.CONFIRMED),
        )

        val report = RuleEvaluator().evaluate(RuleLoader.parse(yaml), types, edges)

        assertEquals(1, report.violations.size)
        val v = report.violations.single()
        assertEquals("controllers-cannot-access-repositories", v.rule)
        assertEquals("com.web.UserController", v.sourceFqn)
        assertEquals("com.data.UserRepository", v.targetFqn)
    }

    @Test
    fun `imports do not trigger call-scoped rules`() {
        val types = listOf(
            type("com.web.UserController", "RestController"),
            type("com.data.UserRepository", "Repository"),
        )
        val edges = listOf(
            DependencyEdge("com.web.UserController", "com.data.UserRepository", EdgeKind.IMPORTS, Confidence.CONFIRMED),
        )

        val report = RuleEvaluator().evaluate(RuleLoader.parse(yaml), types, edges)

        assertTrue(report.violations.isEmpty(), "IMPORTS is not in default edgeKinds; got ${report.violations}")
    }

    @Test
    fun `name pattern rule matches service calling manager`() {
        val types = listOf(
            type("com.service.OrderService"),
            type("com.manager.TaskManager"),
        )
        val edges = listOf(
            DependencyEdge("com.service.OrderService", "com.manager.TaskManager#enqueue", EdgeKind.USES, Confidence.POSSIBLE),
        )

        val report = RuleEvaluator().evaluate(RuleLoader.parse(yaml), types, edges)

        assertEquals(1, report.violations.size)
        assertEquals("services-are-not-managers", report.violations.single().rule)
    }

    @Test
    fun `unmatched stereotypes stay silent`() {
        val types = listOf(
            type("com.service.PlainService"),
            type("com.repo.ThingRepository", "Repository"),
        )
        val edges = listOf(
            DependencyEdge("com.service.PlainService", "com.repo.ThingRepository#save", EdgeKind.CALLS, Confidence.CONFIRMED),
        )

        val report = RuleEvaluator().evaluate(RuleLoader.parse(yaml), types, edges)

        assertTrue(report.violations.isEmpty())
    }

    @Test
    fun `loader returns empty list for missing file`() {
        assertEquals(emptyList(), RuleLoader.load(Files.createTempDirectory("no-rules").resolve("rules.yaml")))
    }
}
