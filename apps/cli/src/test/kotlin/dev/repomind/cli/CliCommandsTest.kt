package dev.repomind.cli

import dev.repomind.core.index.IncrementalIndexer
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliCommandsTest {

    private fun createSampleProject(): Path {
        val root = Files.createTempDirectory("cli-test-repo")
        root.resolve("pom.xml").writeText(
            """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.sample</groupId>
              <artifactId>cli-test</artifactId>
              <version>1.0.0</version>
            </project>
            """.trimIndent(),
        )
        val src = root.resolve("src/main/java/com/sample/service")
        Files.createDirectories(src)
        src.resolve("SampleService.java").writeText(
            """
            package com.sample.service;
            public class SampleService {
                public String run() { return "ok"; }
            }
            """.trimIndent(),
        )
        return root
    }

    @Test
    fun `init command generates rules yaml with selected preset`() {
        val root = createSampleProject()
        val cmd = CommandLine(RepomindCli())

        val exitCode = cmd.execute("init", root.toString(), "--preset=hexagonal")
        assertEquals(0, exitCode)

        val rulesFile = root.resolve(".repomind/rules.yaml")
        assertTrue(Files.isRegularFile(rulesFile))
        val content = Files.readString(rulesFile)
        assertTrue(content.contains("hexagonal-domain-isolation"))
    }

    @Test
    fun `rules command with check-cycles evaluates cleanly on sample repo`() {
        val root = createSampleProject()
        // Index first
        IncrementalIndexer(root.resolve(".repomind/index.db")).update(root)

        val cmd = CommandLine(RepomindCli())
        val outStream = ByteArrayOutputStream()
        val originalOut = System.out
        try {
            System.setOut(PrintStream(outStream))
            val exitCode = cmd.execute("rules", root.toString(), "--preset=three-tier", "--check-cycles")
            assertEquals(0, exitCode)
        } finally {
            System.setOut(originalOut)
        }

        val output = outStream.toString()
        assertTrue(output.contains("evaluatedRules"))
        assertTrue(output.contains("cycleReport"))
    }

    @Test
    fun `watch command runs with timeout and terminates`() {
        val root = createSampleProject()
        val cmd = CommandLine(RepomindCli())

        val exitCode = cmd.execute("watch", root.toString(), "--timeout-ms=500", "--debounce-ms=100", "--quiet")
        assertEquals(0, exitCode)
    }

    @Test
    fun `rules command with suggest-adr mines architecture and writes markdown ADRs`() {
        val root = createSampleProject()
        val webDir = root.resolve("src/main/java/com/sample/web")
        Files.createDirectories(webDir)
        webDir.resolve("SampleController.java").writeText(
            """
            package com.sample.web;
            import com.sample.service.SampleService;
            public class SampleController {
                private SampleService service;
                public String handle() { return service.run(); }
            }
            """.trimIndent(),
        )
        val repoDir = root.resolve("src/main/java/com/sample/repo")
        Files.createDirectories(repoDir)
        repoDir.resolve("SampleRepository.java").writeText(
            """
            package com.sample.repo;
            public class SampleRepository {
                public String query() { return "data"; }
            }
            """.trimIndent(),
        )

        IncrementalIndexer(root.resolve(".repomind/index.db")).update(root)

        val cmd = CommandLine(RepomindCli())
        val exitCode = cmd.execute("rules", root.toString(), "--suggest-adr", "--adr-dir=docs/adr")
        assertEquals(0, exitCode)

        val adrDir = root.resolve("docs/adr")
        assertTrue(Files.isDirectory(adrDir))
        val files = Files.list(adrDir).toList()
        assertTrue(files.isNotEmpty())
        val firstContent = Files.readString(files[0])
        assertTrue(firstContent.contains("# ADR-"))
        assertTrue(firstContent.contains("## Context"))
        assertTrue(firstContent.contains("## RepoMind Architecture Rule"))
    }

    @Test
    fun `deprecations command scans deprecated symbols and generates report`() {
        val root = createSampleProject()
        val legacyDir = root.resolve("src/main/java/com/sample/legacy")
        Files.createDirectories(legacyDir)
        legacyDir.resolve("OldEngine.java").writeText(
            """
            package com.sample.legacy;
            @Deprecated
            public class OldEngine {
                public void start() {}
            }
            """.trimIndent(),
        )

        IncrementalIndexer(root.resolve(".repomind/index.db")).update(root)

        val cmd = CommandLine(RepomindCli())
        val outReport = root.resolve("reports/deprecations.md")
        val exitCode = cmd.execute("deprecations", root.toString(), "--output=" + outReport.toString(), "--json")
        assertEquals(0, exitCode)

        assertTrue(Files.isRegularFile(outReport))
        val reportContent = Files.readString(outReport)
        assertTrue(reportContent.contains("# RepoMind Deprecation Radar & Semantic Drift Report"))
        assertTrue(reportContent.contains("com.sample.legacy.OldEngine"))
    }

    @Test
    fun `refactor command detects dead code and applies modifications with lexical preservation`() {
        val root = createSampleProject()
        val serviceFile = root.resolve("src/main/java/com/sample/service/SampleService.java")
        serviceFile.writeText(
            """
            package com.sample.service;
            public class SampleService {
                public String run() { return "ok"; }
                private void deadMethod() { System.out.println("dead"); }
            }
            """.trimIndent(),
        )

        IncrementalIndexer(root.resolve(".repomind/index.db")).update(root)

        val cmd = CommandLine(RepomindCli())
        // 1. Dry run
        val exitCodeDry = cmd.execute("refactor", root.toString(), "--dead-code")
        assertEquals(0, exitCodeDry)
        assertTrue(Files.readString(serviceFile).contains("deadMethod"), "Dry run must not modify file")

        // 2. Apply
        val exitCodeApply = cmd.execute("refactor", root.toString(), "--dead-code", "--apply", "--create-pr")
        assertEquals(0, exitCodeApply)
        val modified = Files.readString(serviceFile)
        assertFalse(modified.contains("deadMethod"), "Applied refactoring must remove dead method")
        assertTrue(modified.contains("public String run()"), "Active method must be preserved")
    }

    @Test
    fun `federate command links multiple microservices and generates report`() {
        val rootA = createSampleProject()
        val rootB = Files.createTempDirectory("cli-client-repo")
        rootB.resolve("pom.xml").writeText(
            """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.sample</groupId>
              <artifactId>client-service</artifactId>
              <version>1.0.0</version>
            </project>
            """.trimIndent(),
        )
        IncrementalIndexer(rootA.resolve(".repomind/index.db")).update(rootA)
        IncrementalIndexer(rootB.resolve(".repomind/index.db")).update(rootB)

        val cmd = CommandLine(RepomindCli())
        val outReport = rootA.resolve("reports/federation.md")
        val exitCode = cmd.execute("federate", rootA.toString(), rootB.toString(), "--output=" + outReport.toString())
        assertEquals(0, exitCode)
        assertTrue(Files.isRegularFile(outReport))
        val reportContent = Files.readString(outReport)
        assertTrue(reportContent.contains("RepoMind Enterprise Polyrepo Federation Report"))
    }

    @Test
    fun `drift-shield command evaluates architecture boundaries and outputs formatted webhook payload`() {
        val root = createSampleProject()
        IncrementalIndexer(root.resolve(".repomind/index.db")).update(root)

        val cmd = CommandLine(RepomindCli())
        val exitCode = cmd.execute(
            "drift-shield",
            root.toString(),
            "--webhook=https://hooks.slack.com/services/MOCK/TEST/123",
            "--dry-run",
            "--commit=abcdef123456",
            "--branch=test-branch",
        )
        assertEquals(0, exitCode)
    }
}
