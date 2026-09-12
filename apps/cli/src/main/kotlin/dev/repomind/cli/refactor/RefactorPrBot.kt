package dev.repomind.cli.refactor

import dev.repomind.language.java.refactor.FileDiff
import dev.repomind.language.java.refactor.RefactoringResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

@Serializable
data class PrCreationResult(
    val success: Boolean,
    val prUrl: String?,
    val prNumber: Int?,
    val branchName: String,
    val baseBranch: String,
    val title: String,
    val filesChangedCount: Int,
    val transformationsCount: Int,
    val message: String,
)

/**
 * Autonomous CI bot that creates Git branches, commits refactoring diffs,
 * and submits Pull Requests to GitHub REST API.
 */
class RefactorPrBot(
    val repoRoot: Path,
    val token: String? = null,
    val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun executeRefactorPr(
        result: RefactoringResult,
        branchName: String = "repomind/refactor-${System.currentTimeMillis() / 1000}",
        baseBranch: String = "main",
        title: String = "refactor: automated architectural deprecation migration via RepoMind",
        autoPush: Boolean = false,
    ): PrCreationResult {
        if (result.fileDiffs.isEmpty()) {
            return PrCreationResult(
                success = false,
                prUrl = null,
                prNumber = null,
                branchName = branchName,
                baseBranch = baseBranch,
                title = title,
                filesChangedCount = 0,
                transformationsCount = 0,
                message = "No refactoring changes detected; skipped PR creation.",
            )
        }

        val prBody = generatePrMarkdown(result)
        val resolvedToken = token ?: System.getenv("GITHUB_TOKEN") ?: System.getenv("GH_TOKEN")
        val repoSlug = detectGitHubRepositorySlug()

        // 1. Git branch and commit if autoPush or running in CI
        if (autoPush || !resolvedToken.isNullOrBlank()) {
            val branchOk = runGitCommand("checkout", "-b", branchName)
            if (branchOk) {
                for (diff in result.fileDiffs) {
                    runGitCommand("add", diff.filePath)
                }
                runGitCommand("commit", "-m", "$title\n\n${result.totalTransformations} transformations applied.")
                if (autoPush) {
                    runGitCommand("push", "-u", "origin", branchName)
                }
            }
        }

        // 2. Submit GitHub Pull Request via REST API if token & repoSlug are available
        if (!resolvedToken.isNullOrBlank() && repoSlug != null) {
            try {
                val apiUrl = "https://api.github.com/repos/$repoSlug/pulls"
                val payload = buildString {
                    append("{")
                    append("\"title\":").append(json.encodeToString(title)).append(",")
                    append("\"head\":").append(json.encodeToString(branchName)).append(",")
                    append("\"base\":").append(json.encodeToString(baseBranch)).append(",")
                    append("\"body\":").append(json.encodeToString(prBody))
                    append("}")
                }

                val request = HttpRequest.newBuilder(URI.create(apiUrl))
                    .header("Authorization", "Bearer $resolvedToken")
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(Duration.ofSeconds(25))
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in 200..299) {
                    val rootObj = json.parseToJsonElement(response.body()).jsonObject
                    val prUrl = rootObj["html_url"]?.jsonPrimitive?.content
                    val prNum = rootObj["number"]?.jsonPrimitive?.content?.toIntOrNull()
                    return PrCreationResult(
                        success = true,
                        prUrl = prUrl,
                        prNumber = prNum,
                        branchName = branchName,
                        baseBranch = baseBranch,
                        title = title,
                        filesChangedCount = result.totalFilesChanged,
                        transformationsCount = result.totalTransformations,
                        message = "Pull Request successfully created: $prUrl",
                    )
                } else {
                    System.err.println("GitHub API Error ${response.statusCode()}: ${response.body()}")
                }
            } catch (e: Exception) {
                System.err.println("Failed to call GitHub API: ${e.message}")
            }
        }

        // Fallback: Local/Simulation result with generated PR body and commands
        return PrCreationResult(
            success = true,
            prUrl = null,
            prNumber = null,
            branchName = branchName,
            baseBranch = baseBranch,
            title = title,
            filesChangedCount = result.totalFilesChanged,
            transformationsCount = result.totalTransformations,
            message = "Prepared branch '$branchName' with ${result.totalTransformations} transformations across ${result.totalFilesChanged} files. Push to GitHub with: git push -u origin $branchName",
        )
    }

    fun generatePrMarkdown(result: RefactoringResult): String {
        return buildString {
            appendLine("## 🤖 Automated Architectural Refactoring via RepoMind")
            appendLine()
            appendLine("RepoMind detected and migrated architectural debt according to configured repository policies.")
            appendLine()
            appendLine("### Summary of Changes")
            appendLine("- **Total Files Modified**: `${result.totalFilesChanged}`")
            appendLine("- **Total Transformations Applied**: `${result.totalTransformations}`")
            appendLine()
            appendLine("### Detailed File Breakdown")
            appendLine("| File Path | Transformations Applied |")
            appendLine("|---|---|")
            for (diff in result.fileDiffs) {
                val desc = diff.transformationsApplied.joinToString("; ")
                appendLine("| `${diff.filePath}` | $desc |")
            }
            appendLine()
            appendLine("### Diff Preview")
            for (diff in result.fileDiffs.take(5)) {
                appendLine("<details><summary><code>${diff.filePath}</code></summary>")
                appendLine()
                appendLine("```diff")
                appendLine(diff.diffUnified.take(3000))
                appendLine("```")
                appendLine("</details>")
            }
            appendLine()
            appendLine("---")
            appendLine("*Generated autonomously by [RepoMind](https://github.com/daryllrebeiro/repo-mind)*")
        }
    }

    private fun detectGitHubRepositorySlug(): String? {
        val envRepo = System.getenv("GITHUB_REPOSITORY")
        if (!envRepo.isNullOrBlank()) return envRepo.trim()

        return try {
            val process = ProcessBuilder("git", "remote", "get-url", "origin")
                .directory(repoRoot.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val exited = process.waitFor(3, TimeUnit.SECONDS)
            if (exited && process.exitValue() == 0) {
                val url = process.inputStream.bufferedReader().readText().trim()
                extractSlugFromGitUrl(url)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun extractSlugFromGitUrl(url: String): String? {
        // Formats:
        // git@github.com:owner/repo.git
        // https://github.com/owner/repo.git
        val clean = url.removeSuffix(".git").trim()
        return when {
            clean.contains("github.com:") -> clean.substringAfter("github.com:")
            clean.contains("github.com/") -> clean.substringAfter("github.com/")
            else -> null
        }?.trim('/')
    }

    private fun runGitCommand(vararg args: String): Boolean {
        return try {
            val pb = ProcessBuilder("git", *args)
                .directory(repoRoot.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            val proc = pb.start()
            val exited = proc.waitFor(10, TimeUnit.SECONDS)
            exited && proc.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }
}
