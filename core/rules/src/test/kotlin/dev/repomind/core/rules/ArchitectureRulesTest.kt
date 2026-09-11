package dev.repomind.core.rules

import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchitectureRulesTest {

    @Test
    fun `loads default repomind rules yaml file`() {
        val path = Path.of("../../.repomind/rules.yaml")
        val altPath = Path.of(".repomind/rules.yaml")
        val target = if (java.nio.file.Files.exists(altPath)) altPath else path
        if (java.nio.file.Files.exists(target)) {
            val rules = RuleLoader.load(target)
            assertTrue(rules.isNotEmpty())
            assertTrue(rules.any { it.name == "core-must-not-depend-on-apps" })
        }
    }

    @Test
    fun `fails with ArchitectureRuleViolationException when failOnViolation is true`() {
        val rules = listOf(
            RuleDef(
                name = "core-must-not-depend-on-apps",
                from = Stereotype(namePattern = "dev\\.repomind\\.core\\..*"),
                to = Stereotype(namePattern = "dev\\.repomind\\.cli\\..*"),
                message = "Core cannot depend on CLI",
            )
        )
        val types = listOf(
            TypeStereotypeInfo("dev.repomind.core.model.CodeModel", emptyList()),
            TypeStereotypeInfo("dev.repomind.cli.MainCli", emptyList()),
        )
        val edges = listOf(
            DependencyEdge(
                sourceFqn = "dev.repomind.core.model.CodeModel#load",
                targetFqn = "dev.repomind.cli.MainCli#run",
                kind = EdgeKind.CALLS,
                confidence = Confidence.CONFIRMED,
                line = 42,
            )
        )

        val evaluator = RuleEvaluator()
        val ex = assertThrows<ArchitectureRuleViolationException> {
            evaluator.evaluate(rules, types, edges, failOnViolation = true)
        }

        assertEquals(1, ex.violations.size)
        val violation = ex.violations.single()
        assertEquals("core-must-not-depend-on-apps", violation.rule)
        assertEquals(42, violation.line)
        assertTrue(ex.message!!.contains("line 42"))
    }

    @Test
    fun `evaluates cleanly when no architectural boundaries are violated`() {
        val rules = listOf(
            RuleDef(
                name = "core-must-not-depend-on-apps",
                from = Stereotype(namePattern = "dev\\.repomind\\.core\\..*"),
                to = Stereotype(namePattern = "dev\\.repomind\\.cli\\..*"),
            )
        )
        val types = listOf(
            TypeStereotypeInfo("dev.repomind.cli.MainCli", emptyList()),
            TypeStereotypeInfo("dev.repomind.core.model.CodeModel", emptyList()),
        )
        // CLI calling Core is completely valid
        val edges = listOf(
            DependencyEdge(
                sourceFqn = "dev.repomind.cli.MainCli#run",
                targetFqn = "dev.repomind.core.model.CodeModel#load",
                kind = EdgeKind.CALLS,
                confidence = Confidence.CONFIRMED,
                line = 18,
            )
        )

        val report = RuleEvaluator().evaluate(rules, types, edges, failOnViolation = true)
        assertTrue(report.passed)
        assertEquals(0, report.violations.size)
    }

    @Test
    fun `package-level regex matches nested classes across packages`() {
        val rules = listOf(
            RuleDef(
                name = "domain-isolation",
                from = Stereotype(namePattern = "com\\.app\\.domain\\..*"),
                to = Stereotype(namePattern = "com\\.app\\.infra\\..*"),
            )
        )
        val types = listOf(
            TypeStereotypeInfo("com.app.domain.model.Order", emptyList()),
            TypeStereotypeInfo("com.app.infra.db.OrderDao", emptyList()),
        )
        val edges = listOf(
            DependencyEdge(
                sourceFqn = "com.app.domain.model.Order",
                targetFqn = "com.app.infra.db.OrderDao",
                kind = EdgeKind.USES,
                confidence = Confidence.CONFIRMED,
                line = 25,
            )
        )

        val report = RuleEvaluator().evaluate(rules, types, edges)
        assertFalse(report.passed)
        assertEquals(1, report.violations.size)
        assertEquals(25, report.violations.single().line)
    }
}
