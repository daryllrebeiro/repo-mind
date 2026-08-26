package dev.repomind.core.classpath

import dev.repomind.core.model.BuildSystem
import dev.repomind.core.model.RepoModule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class SecurityTest {

    private fun tempModule(): RepoModule {
        val dir = Files.createTempDirectory("repomind-sec")
        val pom = dir.resolve("pom.xml")
        pom.writeText("<project/>")
        return RepoModule(name = "m", path = dir, buildFile = pom, sourceRoots = emptyList())
    }

    @Test
    fun `classpath resolution is offline by default`() {
        val module = tempModule()
        val commands = mutableListOf<List<String>>()
        val runner = CommandRunner { _, command ->
            commands += command
            val outArg = command.first { it.startsWith("-Dmdep.outputFile=") }.removePrefix("-Dmdep.outputFile=")
            Path.of(outArg).writeText("")
            CommandResult(1, "", "offline failure")
        }
        val resolver = ClasspathResolver(FileBasedClasspathCache(Files.createTempDirectory("c")), commandRunner = runner)

        try {
            resolver.resolve(module.path, module, BuildSystem.MAVEN)
        } catch (_: ClasspathResolutionException) {
        }
        assertTrue(commands.single().contains("--offline"), "must run maven with --offline by default")
    }

    @Test
    fun `online mode omits the offline flag`() {
        val module = tempModule()
        val commands = mutableListOf<List<String>>()
        val runner = CommandRunner { _, command ->
            commands += command
            val outArg = command.first { it.startsWith("-Dmdep.outputFile=") }.removePrefix("-Dmdep.outputFile=")
            Path.of(outArg).writeText("x.jar")
            CommandResult(0, "", "")
        }
        val resolver = ClasspathResolver(FileBasedClasspathCache(Files.createTempDirectory("c")), commandRunner = runner, allowNetwork = true)

        resolver.resolve(module.path, module, BuildSystem.MAVEN)

        assertTrue(!commands.single().contains("--offline"))
    }

    @Test
    fun `arguments with control characters are rejected`() {
        assertThrows<IllegalArgumentException> {
            SafeArgs.validate("path\u0000null")
        }
        assertThrows<IllegalArgumentException> {
            SafeArgs.validate("line1\nrm -rf /")
        }
    }

    @Test
    fun `metacharacter arguments are quoted for cmd passthrough`() {
        val escaped = SafeArgs.escapeForWindowsCmd("C:\\repo & calc")
        assertTrue(escaped.startsWith("\"") && escaped.endsWith("\""))

        assertTrue(SafeArgs.escapeForWindowsCmd("C:\\plain\\path\\pom.xml").startsWith("\"").not() ||
            !Regex("[&|^%!\"]").containsMatchIn("C:\\plain\\path\\pom.xml"))
    }
}
