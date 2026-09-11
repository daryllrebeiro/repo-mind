package dev.repomind.integration

import dev.repomind.core.config.BindingKind
import dev.repomind.core.config.ConfigurationExtractor
import dev.repomind.core.scanner.RepositoryScanner
import dev.repomind.language.java.JavaSemanticParser
import dev.repomind.storage.sqlite.SymbolDatabase
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpringRepoIndexingIntegrationTest {

    private fun findExamplesDir(): Path {
        val candidates = listOf(
            Path.of("java-examples-for-repo-mind"),
            Path.of("../../java-examples-for-repo-mind"),
            Path.of("../java-examples-for-repo-mind"),
        )
        return candidates.firstOrNull { Files.isDirectory(it) }?.toAbsolutePath()?.normalize()
            ?: error("java-examples-for-repo-mind directory not found")
    }

    @Test
    fun `full scan parse index pipeline on gs-rest-service`() {
        val repoDir = findExamplesDir().resolve("gs-rest-service/complete")
        assertTrue(Files.isDirectory(repoDir), "gs-rest-service directory must exist")

        val scan = RepositoryScanner().scan(repoDir)
        assertTrue(scan.modules.isNotEmpty(), "Expected at least 1 module")

        val parser = JavaSemanticParser()
        val tempDb = Files.createTempFile("repomind-gs-test", ".db")
        tempDb.toFile().deleteOnExit()

        SymbolDatabase.open(tempDb).use { db ->
            var totalSymbols = 0
            var totalEdges = 0

            for (module in scan.modules) {
                db.recordModule(module.name, module.path.toString(), scan.buildSystem.name)
                val parsed = parser.parseModule(module, emptyList())
                totalSymbols += db.replaceModule(module.name, parsed)
                totalEdges += db.edges.replaceModule(module.name, parsed.edges)
            }

            assertTrue(totalSymbols >= 3, "Expected at least 3 types parsed in gs-rest-service, got $totalSymbols")
            assertTrue(db.count() > 0, "SymbolDatabase count must be positive")

            val startTime = System.nanoTime()
            val lookup = db.findByNamePrefix("Greeting")
            val lookupDurationMs = (System.nanoTime() - startTime) / 1_000_000

            assertTrue(lookup.isNotEmpty(), "Lookup for 'Greeting' should find symbols")
            assertTrue(lookupDurationMs < 500, "Symbol lookup should complete sub-second, took ${lookupDurationMs}ms")

            val report = db.confidenceReport()
            assertTrue(report.totalSymbols > 0)
            assertTrue(report.confidenceRate in 0.0..1.0)
        }
    }

    @Test
    fun `full scan parse index pipeline on spring-petclinic`() {
        val repoDir = findExamplesDir().resolve("spring-petclinic")
        assertTrue(Files.isDirectory(repoDir), "spring-petclinic directory must exist")

        val scan = RepositoryScanner().scan(repoDir)
        assertTrue(scan.modules.isNotEmpty(), "Expected at least 1 module in spring-petclinic")

        val parser = JavaSemanticParser()
        val tempDb = Files.createTempFile("repomind-petclinic-test", ".db")
        tempDb.toFile().deleteOnExit()

        SymbolDatabase.open(tempDb).use { db ->
            var totalSymbols = 0
            var totalEdges = 0

            for (module in scan.modules) {
                db.recordModule(module.name, module.path.toString(), scan.buildSystem.name)
                val parsed = parser.parseModule(module, emptyList())
                totalSymbols += db.replaceModule(module.name, parsed)
                totalEdges += db.edges.replaceModule(module.name, parsed.edges)

                val configExtractor = ConfigurationExtractor()
                val configGraph = configExtractor.extract(module, parsed)
                assertTrue(configGraph.properties.isNotEmpty(), "Expected extracted properties from petclinic resources")
            }

            assertTrue(totalSymbols > 50, "Expected >50 symbols in spring-petclinic, got $totalSymbols")
            assertTrue(totalEdges > 20, "Expected >20 edges in spring-petclinic, got $totalEdges")

            val startTime = System.nanoTime()
            val ownerSymbols = db.findByFqn("org.springframework.samples.petclinic.owner.Owner")
            val lookupMs = (System.nanoTime() - startTime) / 1_000_000

            assertTrue(ownerSymbols.isNotEmpty(), "Owner entity symbol must be found in database")
            assertTrue(lookupMs < 500, "FQN lookup must be sub-second, took ${lookupMs}ms")

            val report = db.confidenceReport()
            assertTrue(report.totalSymbols > 0)
            assertTrue(report.confidenceRate in 0.0..1.0)

            val pkgDeps = db.edges.packageDependencies()
            assertTrue(pkgDeps.isNotEmpty(), "Expected non-empty package-level dependency rollup")
        }
    }

    @Test
    fun `full scan parse index pipeline on multi-module piggymetrics`() {
        val repoDir = findExamplesDir().resolve("piggymetrics")
        assertTrue(Files.isDirectory(repoDir), "piggymetrics directory must exist")

        val scan = RepositoryScanner().scan(repoDir)
        assertTrue(scan.modules.size >= 5, "Expected multiple modules in piggymetrics, found ${scan.modules.size}")

        val parser = JavaSemanticParser()
        val tempDb = Files.createTempFile("repomind-piggymetrics-test", ".db")
        tempDb.toFile().deleteOnExit()

        SymbolDatabase.open(tempDb).use { db ->
            var totalSymbols = 0
            var totalEdges = 0

            for (module in scan.modules) {
                db.recordModule(module.name, module.path.toString(), scan.buildSystem.name)
                val parsed = parser.parseModule(module, emptyList())
                totalSymbols += db.replaceModule(module.name, parsed)
                totalEdges += db.edges.replaceModule(module.name, parsed.edges)
            }

            assertTrue(totalSymbols > 100, "Expected >100 symbols in piggymetrics, got $totalSymbols")
            assertTrue(totalEdges > 50, "Expected >50 edges in piggymetrics, got $totalEdges")

            val typeToModule = db.typeToModuleMap()
            assertTrue(typeToModule.isNotEmpty(), "Expected non-empty type-to-module mapping")

            val report = db.confidenceReport()
            assertTrue(report.totalSymbols > 0)
            assertTrue(report.confidenceRate in 0.0..1.0)
            assertTrue(report.totalEdges > 0)
        }
    }
}
