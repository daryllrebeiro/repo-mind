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
}
