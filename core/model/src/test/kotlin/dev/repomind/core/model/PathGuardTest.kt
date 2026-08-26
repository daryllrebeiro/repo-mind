package dev.repomind.core.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathGuardTest {

    @Test
    fun `resolveUnder rejects absolute paths and traversal escapes`() {
        val root = Files.createTempDirectory("guard-root")

        assertThrows<IllegalArgumentException> { PathGuard.resolveUnder(root, "/etc/passwd") }
        assertThrows<IllegalArgumentException> { PathGuard.resolveUnder(root, "../../etc/passwd") }
    }

    @Test
    fun `resolveUnder allows legitimate nested paths`() {
        val root = Files.createTempDirectory("guard-root")
        Files.createDirectories(root.resolve("src/main/java"))
        root.resolve("src/main/java/A.java").writeText("class A {}")

        val resolved = PathGuard.resolveUnder(root, "src/main/java/A.java")

        assertTrue(resolved.startsWith(root.toAbsolutePath().normalize()))
    }

    @Test
    fun `requireDirectory fails loudly on missing path`() {
        assertThrows<IllegalArgumentException> {
            PathGuard.requireDirectory(Files.createTempDirectory("gone").resolve("nope"))
        }
    }

    @Test
    fun `requireRegularFile returns normalized path when present`() {
        val root = Files.createTempDirectory("guard-root")
        val f = root.resolve("index.db")
        f.writeText("x")

        assertEquals(f.toAbsolutePath().normalize(), PathGuard.requireRegularFile(f, "index"))
    }
}
