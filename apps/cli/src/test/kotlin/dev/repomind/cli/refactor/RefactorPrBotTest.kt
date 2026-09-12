package dev.repomind.cli.refactor

import dev.repomind.language.java.refactor.FileDiff
import dev.repomind.language.java.refactor.RefactoringResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefactorPrBotTest {

    @Test
    fun `extractSlugFromGitUrl handles SSH and HTTPS formats`(@TempDir tempDir: Path) {
        val bot = RefactorPrBot(tempDir)
        assertEquals("daryllrebeiro/repo-mind", bot.extractSlugFromGitUrl("git@github.com:daryllrebeiro/repo-mind.git"))
        assertEquals("daryllrebeiro/repo-mind", bot.extractSlugFromGitUrl("https://github.com/daryllrebeiro/repo-mind.git"))
        assertEquals("owner/sample-service", bot.extractSlugFromGitUrl("https://github.com/owner/sample-service"))
        assertNull(bot.extractSlugFromGitUrl("https://gitlab.com/owner/repo.git"))
    }

    @Test
    fun `generatePrMarkdown outputs markdown table and diff details`(@TempDir tempDir: Path) {
        val bot = RefactorPrBot(tempDir)
        val result = RefactoringResult(
            totalFilesChanged = 1,
            totalTransformations = 2,
            fileDiffs = listOf(
                FileDiff(
                    filePath = "src/main/java/OrderService.java",
                    originalContent = "old code",
                    modifiedContent = "new code",
                    diffUnified = "--- a/OrderService.java\n+++ b/OrderService.java\n- old code\n+ new code",
                    transformationsApplied = listOf("Migrated deprecated method getLegacyId()"),
                )
            ),
            appliedToDisk = false,
        )

        val markdown = bot.generatePrMarkdown(result)
        assertTrue(markdown.contains("Automated Architectural Refactoring via RepoMind"))
        assertTrue(markdown.contains("`src/main/java/OrderService.java`"))
        assertTrue(markdown.contains("Migrated deprecated method getLegacyId()"))
        assertTrue(markdown.contains("```diff"))
    }

    @Test
    fun `executeRefactorPr skips empty results gracefully`(@TempDir tempDir: Path) {
        val bot = RefactorPrBot(tempDir)
        val emptyResult = RefactoringResult(
            totalFilesChanged = 0,
            totalTransformations = 0,
            fileDiffs = emptyList(),
            appliedToDisk = false,
        )

        val prResult = bot.executeRefactorPr(emptyResult)
        assertFalse(prResult.success)
        assertTrue(prResult.message.contains("No refactoring changes detected"))
    }

    @Test
    fun `executeRefactorPr returns fallback instructions when offline or no remote`(@TempDir tempDir: Path) {
        val bot = RefactorPrBot(tempDir)
        val result = RefactoringResult(
            totalFilesChanged = 1,
            totalTransformations = 1,
            fileDiffs = listOf(
                FileDiff(
                    filePath = "Test.java",
                    originalContent = "A",
                    modifiedContent = "B",
                    diffUnified = "- A\n+ B",
                    transformationsApplied = listOf("Removed dead method foo"),
                )
            ),
            appliedToDisk = false,
        )

        val prResult = bot.executeRefactorPr(
            result = result,
            branchName = "repomind/unit-test-branch",
            baseBranch = "main",
            title = "refactor: test deprecation cleanup",
        )

        assertTrue(prResult.success)
        assertEquals("repomind/unit-test-branch", prResult.branchName)
        assertEquals(1, prResult.filesChangedCount)
        assertEquals(1, prResult.transformationsCount)
        assertTrue(prResult.message.contains("Prepared branch 'repomind/unit-test-branch'"))
    }
}
