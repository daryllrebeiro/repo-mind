package dev.repomind.core.scanner

import dev.repomind.core.model.BuildSystem
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepositoryScannerTest {

    private val fixture: Path = Path.of("..", "..", "tests", "fixtures", "sample-maven-repo")
        .toAbsolutePath().normalize()

    @Test
    fun `detects maven multi-module layout`() {
        val result = RepositoryScanner().scan(fixture)

        assertEquals(BuildSystem.MAVEN, result.buildSystem)
        assertEquals(2, result.modules.size)
        assertEquals(setOf("sample-maven-repo", "server"), result.modules.map { it.name }.toSet())
    }

    @Test
    fun `discovers source roots and files while respecting gitignore`() {
        val result = RepositoryScanner().scan(fixture)

        assertTrue(result.sourceFileCount == 2, "expected 2 source files, got ${result.sourceFileCount}")
        val server = result.modules.first { it.name == "server" }
        assertEquals(1, server.sourceRoots.size)
        assertTrue(server.sourceRoots.single().path.fileName.toString() == "java")
        assertTrue(!server.sourceRoots.single().isTest)
    }

    @Test
    fun `records build files per module`() {
        val result = RepositoryScanner().scan(fixture)

        result.modules.forEach { module ->
            assertEquals("pom.xml", module.buildFile?.fileName?.toString())
        }
    }
}
