package dev.repomind.core.rules.hook

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHookManagerTest {

    @Test
    fun `install fails when not a git repository`(@TempDir tempDir: Path) {
        val result = GitHookManager.install(tempDir)
        assertFalse(result.success)
        assertTrue(result.message.contains("not a Git repository"))
    }

    @Test
    fun `install succeeds in git repository and writes pre-commit script`(@TempDir tempDir: Path) {
        val gitDir = tempDir.resolve(".git")
        Files.createDirectories(gitDir)

        val result = GitHookManager.install(tempDir, HookType.PRE_COMMIT, failOnViolation = true)
        assertTrue(result.success)
        assertTrue(Files.isRegularFile(result.hookPath))

        val content = Files.readString(result.hookPath)
        assertTrue(content.contains("# RepoMind Automated Architecture Guard Hook"))
        assertTrue(content.contains("repomind rules . --fail-on-violation"))

        assertTrue(GitHookManager.isInstalled(tempDir, HookType.PRE_COMMIT))

        // Uninstall
        val uninstalled = GitHookManager.uninstall(tempDir, HookType.PRE_COMMIT)
        assertTrue(uninstalled)
        assertFalse(GitHookManager.isInstalled(tempDir, HookType.PRE_COMMIT))
        assertFalse(Files.exists(result.hookPath))
    }

    @Test
    fun `install pre-push hook creates pre-push file`(@TempDir tempDir: Path) {
        val gitDir = tempDir.resolve(".git")
        Files.createDirectories(gitDir)

        val result = GitHookManager.install(tempDir, HookType.PRE_PUSH)
        assertTrue(result.success)
        assertEquals("pre-push", result.hookPath.fileName.toString())
        assertTrue(GitHookManager.isInstalled(tempDir, HookType.PRE_PUSH))
    }
}
