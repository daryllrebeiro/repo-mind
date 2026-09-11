package dev.repomind.core.rules

import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdrGeneratorTest {

    @Test
    fun `mines presentation to persistence ADR when controller and repository exist`(@TempDir tempDir: Path) {
        val types = listOf(
            TypeStereotypeInfo("com.example.web.UserController", listOf("RestController")),
            TypeStereotypeInfo("com.example.service.UserService", listOf("Service")),
            TypeStereotypeInfo("com.example.repo.UserRepository", listOf("Repository")),
        )
        val edges = listOf(
            DependencyEdge("com.example.web.UserController#getUser", "com.example.service.UserService#findUser", EdgeKind.CALLS, Confidence.CONFIRMED),
            DependencyEdge("com.example.service.UserService#findUser", "com.example.repo.UserRepository#selectById", EdgeKind.CALLS, Confidence.CONFIRMED),
        )

        val adrs = AdrGenerator.generateAdrs(types, edges, basePackage = "com.example")
        val presentationAdr = adrs.firstOrNull { it.title.contains("Persistence Access") }
        assertNotNull(presentationAdr)
        assertEquals("Accepted", presentationAdr.status)
        assertTrue(presentationAdr.context.contains("0 direct couplings"))
        assertTrue(presentationAdr.yamlSnippet.contains("no-presentation-to-persistence"))

        val writtenPaths = AdrGenerator.writeAdrs(listOf(presentationAdr), tempDir)
        assertEquals(1, writtenPaths.size)
        assertTrue(Files.exists(writtenPaths[0]))
        val content = Files.readString(writtenPaths[0])
        assertTrue(content.contains("# ADR-0001: Encapsulate Persistence Access within Service Layer"))
        assertTrue(content.contains("## Context"))
        assertTrue(content.contains("## Decision"))
        assertTrue(content.contains("## Consequences"))
        assertTrue(content.contains("## RepoMind Architecture Rule"))
    }

    @Test
    fun `mines domain to infrastructure independence ADR`() {
        val types = listOf(
            TypeStereotypeInfo("com.myapp.domain.Order", emptyList()),
            TypeStereotypeInfo("com.myapp.infrastructure.PostgresRepo", emptyList()),
        )
        val edges = listOf(
            DependencyEdge("com.myapp.infrastructure.PostgresRepo#save", "com.myapp.domain.Order#getId", EdgeKind.CALLS, Confidence.CONFIRMED),
        )

        val adrs = AdrGenerator.generateAdrs(types, edges, basePackage = "com.myapp")
        val domainAdr = adrs.firstOrNull { it.title.contains("Domain Model Independence") }
        assertNotNull(domainAdr)
        assertEquals("Accepted", domainAdr.status)
        assertTrue(domainAdr.context.contains("Observed dependencies from domain to infrastructure: 0"))
        assertTrue(domainAdr.yamlSnippet.contains("domain-must-not-depend-on-infrastructure"))
    }

    @Test
    fun `mines acyclic package ADR when no package cycles exist`() {
        val types = listOf(
            TypeStereotypeInfo("com.pkg.a.ClassA", emptyList()),
            TypeStereotypeInfo("com.pkg.b.ClassB", emptyList()),
        )
        val edges = listOf(
            DependencyEdge("com.pkg.a.ClassA#call", "com.pkg.b.ClassB#doWork", EdgeKind.CALLS, Confidence.CONFIRMED),
        )

        val adrs = AdrGenerator.generateAdrs(types, edges, basePackage = "com.pkg")
        val acyclicAdr = adrs.firstOrNull { it.title.contains("Circular Package Dependencies") }
        assertNotNull(acyclicAdr)
        assertEquals("Accepted", acyclicAdr.status)
        assertTrue(acyclicAdr.context.contains("0 circular package dependencies"))
    }

    @Test
    fun `mines unidirectional dependency between subsystems`() {
        val types = listOf(
            TypeStereotypeInfo("com.corp.feature.FeatureService", emptyList()),
            TypeStereotypeInfo("com.corp.common.CommonHelper", emptyList()),
        )
        val edges = listOf(
            DependencyEdge("com.corp.feature.FeatureService#step1", "com.corp.common.CommonHelper#h1", EdgeKind.CALLS, Confidence.CONFIRMED),
            DependencyEdge("com.corp.feature.FeatureService#step2", "com.corp.common.CommonHelper#h2", EdgeKind.CALLS, Confidence.CONFIRMED),
            DependencyEdge("com.corp.feature.FeatureService#step3", "com.corp.common.CommonHelper#h3", EdgeKind.CALLS, Confidence.CONFIRMED),
        )

        val adrs = AdrGenerator.generateAdrs(types, edges, basePackage = "com.corp")
        val unidirAdr = adrs.firstOrNull { it.title.contains("Unidirectional Dependency Flow") }
        assertNotNull(unidirAdr)
        assertTrue(unidirAdr.title.contains("feature to common"))
        assertTrue(unidirAdr.yamlSnippet.contains("unidirectional-feature-to-common"))
    }
}
