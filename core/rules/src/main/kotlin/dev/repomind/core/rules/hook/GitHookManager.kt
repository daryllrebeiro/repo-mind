package dev.repomind.core.rules.hook

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

enum class HookType(val filename: String) {
    PRE_COMMIT("pre-commit"),
    PRE_PUSH("pre-push"),
}

data class HookInstallResult(
    val success: Boolean,
    val hookPath: Path,
    val message: String,
)

/**
 * Manages automated Git pre-commit and pre-push hooks to enforce
 * architecture boundary rules prior to committing or pushing code.
 */
object GitHookManager {

    private const val HEADER_SIGNATURE = "# RepoMind Automated Architecture Guard Hook"

    fun install(
        repoRoot: Path,
        hookType: HookType = HookType.PRE_COMMIT,
        failOnViolation: Boolean = true,
    ): HookInstallResult {
        val gitDir = repoRoot.resolve(".git")
        if (!gitDir.exists() || !Files.isDirectory(gitDir)) {
            return HookInstallResult(
                success = false,
                hookPath = gitDir.resolve("hooks/${hookType.filename}"),
                message = "Directory is not a Git repository (missing .git directory): $repoRoot",
            )
        }

        val hooksDir = gitDir.resolve("hooks")
        if (!hooksDir.exists()) {
            Files.createDirectories(hooksDir)
        }

        val hookFile = hooksDir.resolve(hookType.filename)
        val scriptContent = buildHookScript(hookType, failOnViolation)

        Files.writeString(hookFile, scriptContent)
        try {
            hookFile.toFile().setExecutable(true, false)
        } catch (_: Exception) {
            // Ignore on platforms where POSIX permissions are not supported
        }

        return HookInstallResult(
            success = true,
            hookPath = hookFile,
            message = "Successfully installed RepoMind ${hookType.filename} hook at $hookFile",
        )
    }

    fun uninstall(repoRoot: Path, hookType: HookType = HookType.PRE_COMMIT): Boolean {
        val hookFile = repoRoot.resolve(".git/hooks/${hookType.filename}")
        if (!hookFile.isRegularFile()) return false

        val content = Files.readString(hookFile)
        if (content.contains(HEADER_SIGNATURE)) {
            Files.deleteIfExists(hookFile)
            return true
        }
        return false
    }

    fun isInstalled(repoRoot: Path, hookType: HookType = HookType.PRE_COMMIT): Boolean {
        val hookFile = repoRoot.resolve(".git/hooks/${hookType.filename}")
        if (!hookFile.isRegularFile()) return false
        val content = Files.readString(hookFile)
        return content.contains(HEADER_SIGNATURE)
    }

    private fun buildHookScript(hookType: HookType, failOnViolation: Boolean): String {
        val failFlag = if (failOnViolation) "--fail-on-violation" else ""
        return """
            #!/bin/sh
            $HEADER_SIGNATURE
            # Generated automatically by 'repomind hook install'
            
            echo "🛡️  RepoMind: Verifying architectural boundaries before ${hookType.name.lowercase().replace('_', ' ')}..."
            
            if command -v repomind >/dev/null 2>&1; then
                repomind rules . $failFlag
                RESULT=${'$'}?
            elif [ -f "./gradlew" ]; then
                ./gradlew :cli:run --args="rules . $failFlag" --quiet
                RESULT=${'$'}?
            elif [ -f "./repomind" ]; then
                ./repomind rules . $failFlag
                RESULT=${'$'}?
            else
                echo "⚠️  RepoMind executable not found on PATH; skipping architectural check."
                exit 0
            fi
            
            if [ ${'$'}RESULT -ne 0 ]; then
                echo "❌ RepoMind: Architectural boundary violations detected. Commit rejected."
                echo "💡 Run 'repomind rules .' to inspect violations or update .repomind/rules.yaml"
                exit 1
            fi
            
            echo "✅ RepoMind: Architectural boundaries verified successfully."
            exit 0
        """.trimIndent() + "\n"
    }
}
