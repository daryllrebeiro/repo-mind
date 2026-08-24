package dev.repomind.core.classpath

import dev.repomind.core.model.BuildSystem
import dev.repomind.core.model.RepoModule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClasspathResolverTest {

    private fun tempModule(): Triple<Path, Path, RepoModule> {
        val dir = Files.createTempDirectory("repomind-module")
        val pom = dir.resolve("pom.xml")
        pom.writeText("<project/>")
        return Triple(dir, pom, RepoModule(name = "test-module", path = dir, buildFile = pom, sourceRoots = emptyList()))
    }

    private fun mavenRunnerReturning(classpath: String): Pair<CommandRunner, MutableList<List<String>>> {
        val invocations = mutableListOf<List<String>>()
        val runner = CommandRunner { _, command ->
            invocations += command
            val outArg = command.first { it.startsWith("-Dmdep.outputFile=") }.removePrefix("-Dmdep.outputFile=")
            Path.of(outArg).writeText(classpath)
            CommandResult(0, "", "")
        }
        return runner to invocations
    }

    @Test
    fun `parses resolved classpath and caches it`() {
        val (dir, _, module) = tempModule()
        val jar1 = dir.resolve("dep1.jar").toAbsolutePath()
        val jar2 = dir.resolve("dep2.jar").toAbsolutePath()
        val cp = listOf(jar1.toString(), jar2.toString()).joinToString(File.pathSeparator)
        val (runner, invocations) = mavenRunnerReturning(cp)
        val resolver = ClasspathResolver(cache = FileBasedClasspathCache(Files.createTempDirectory("cp-cache")), commandRunner = runner)

        val first = resolver.resolve(dir.parent ?: dir, module, BuildSystem.MAVEN)
        assertEquals(listOf(jar1, jar2), first.entries)
        assertFalse(first.fromCache)
        assertEquals(1, invocations.size)

        val second = resolver.resolve(dir.parent ?: dir, module, BuildSystem.MAVEN)
        assertTrue(second.fromCache)
        assertEquals(1, invocations.size, "second resolve must be served from cache")
    }

    @Test
    fun `invalidates cache when build file content changes`() {
        val (dir, pom, module) = tempModule()
        val cp = dir.resolve("a.jar").toString()
        val (runner, invocations) = mavenRunnerReturning(cp)
        val resolver = ClasspathResolver(cache = FileBasedClasspathCache(Files.createTempDirectory("cp-cache")), commandRunner = runner)

        resolver.resolve(dir, module, BuildSystem.MAVEN)
        pom.writeText("<project changed='true'/>")
        val second = resolver.resolve(dir, module, BuildSystem.MAVEN)

        assertFalse(second.fromCache)
        assertEquals(2, invocations.size, "changed build file must invalidate the cache entry")
    }

    @Test
    fun `fails loudly when the build tool fails`() {
        val (_, _, module) = tempModule()
        val runner = CommandRunner { _, _ -> CommandResult(1, "", "[ERROR] Could not resolve dependencies") }
        val resolver = ClasspathResolver(cache = FileBasedClasspathCache(Files.createTempDirectory("cp-cache")), commandRunner = runner)

        val ex = assertThrows<ClasspathResolutionException> {
            resolver.resolve(module.path, module, BuildSystem.MAVEN)
        }
        assertTrue(ex.message!!.contains("Maven classpath resolution failed"))
        assertTrue(ex.stderr.orEmpty().contains("Could not resolve"))
    }

    @Test
    fun `unknown build system is a loud failure not a silent empty classpath`() {
        val (_, _, module) = tempModule()
        val resolver = ClasspathResolver(cache = FileBasedClasspathCache(Files.createTempDirectory("cp-cache")), commandRunner = CommandRunner { _, _ -> CommandResult(0, "", "") })

        assertThrows<ClasspathResolutionException> {
            resolver.resolve(module.path, module, BuildSystem.UNKNOWN)
        }
    }
}
