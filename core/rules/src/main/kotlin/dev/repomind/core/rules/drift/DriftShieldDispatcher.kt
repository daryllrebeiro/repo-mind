package dev.repomind.core.rules.drift

import dev.repomind.core.rules.Violation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

@Serializable
enum class WebhookProvider {
    SLACK,
    TEAMS,
    DISCORD,
    GENERIC,
}

@Serializable
data class DriftAlert(
    val repoName: String,
    val commitSha: String? = null,
    val branch: String? = null,
    val author: String? = null,
    val violations: List<Violation> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
) {
    val totalViolations: Int get() = violations.size
    val isBlocker: Boolean get() = violations.isNotEmpty()
    val severity: String
        get() = when {
            violations.size >= 5 -> "CRITICAL"
            violations.isNotEmpty() -> "HIGH"
            else -> "CLEAN"
        }
}

@Serializable
data class WebhookDispatchResult(
    val success: Boolean,
    val provider: WebhookProvider,
    val statusCode: Int?,
    val message: String,
    val payloadFormatted: String,
)

/**
 * Live Architectural Drift Shield Webhook Dispatcher.
 * Transmits real-time architecture boundary violation alerts to Slack, Microsoft Teams,
 * Discord, and custom generic webhook endpoints when architectural drift occurs.
 */
class DriftShieldDispatcher(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun detectProvider(webhookUrl: String): WebhookProvider {
        val lower = webhookUrl.lowercase()
        return when {
            lower.contains("hooks.slack.com") -> WebhookProvider.SLACK
            lower.contains("office.com") || lower.contains("logic.azure.com") -> WebhookProvider.TEAMS
            lower.contains("discord.com/api/webhooks") || lower.contains("discordapp.com/api/webhooks") -> WebhookProvider.DISCORD
            else -> WebhookProvider.GENERIC
        }
    }

    fun formatPayload(alert: DriftAlert, provider: WebhookProvider): String {
        return when (provider) {
            WebhookProvider.SLACK -> formatSlackPayload(alert)
            WebhookProvider.TEAMS -> formatTeamsPayload(alert)
            WebhookProvider.DISCORD -> formatDiscordPayload(alert)
            WebhookProvider.GENERIC -> formatGenericPayload(alert)
        }
    }

    fun dispatchAlert(
        webhookUrl: String,
        alert: DriftAlert,
        providerOverride: WebhookProvider? = null,
        dryRun: Boolean = false,
    ): WebhookDispatchResult {
        val provider = providerOverride ?: detectProvider(webhookUrl)
        val payload = formatPayload(alert, provider)

        if (dryRun) {
            return WebhookDispatchResult(
                success = true,
                provider = provider,
                statusCode = null,
                message = "Dry-run successful: alert formatted for $provider without sending network request.",
                payloadFormatted = payload,
            )
        }

        return try {
            val request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .header("User-Agent", "RepoMind-DriftShield/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(15))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val ok = response.statusCode() in 200..299
            WebhookDispatchResult(
                success = ok,
                provider = provider,
                statusCode = response.statusCode(),
                message = if (ok) "Alert successfully delivered to $provider" else "Webhook delivery failed with HTTP ${response.statusCode()}: ${response.body()}",
                payloadFormatted = payload,
            )
        } catch (e: Exception) {
            WebhookDispatchResult(
                success = false,
                provider = provider,
                statusCode = null,
                message = "Exception dispatching webhook to $provider: ${e.message}",
                payloadFormatted = payload,
            )
        }
    }

    fun formatSlackPayload(alert: DriftAlert): String {
        val headerText = if (alert.isBlocker) {
            "🚨 *RepoMind Architectural Drift Shield: ${alert.totalViolations} Violation(s) Detected*"
        } else {
            "✅ *RepoMind Architectural Drift Shield: Architecture Boundaries Clean*"
        }

        val details = alert.violations.take(8).joinToString("\n") { v ->
            val lineStr = if (v.line > 0) ":${v.line}" else ""
            "• `[${v.rule}]` `${v.sourceFqn}$lineStr` → `${v.targetFqn}` [${v.edgeKind}]: ${v.message ?: "Illegal dependency"}"
        } + if (alert.violations.size > 8) "\n...and ${alert.violations.size - 8} more violation(s)" else ""

        val root = buildJsonObject {
            put("text", headerText)
            putJsonArray("blocks") {
                add(buildJsonObject {
                    put("type", "header")
                    putJsonObject("text") {
                        put("type", "plain_text")
                        put("text", "🛡️ RepoMind Architectural Drift Alert")
                        put("emoji", true)
                    }
                })
                add(buildJsonObject {
                    put("type", "section")
                    putJsonArray("fields") {
                        add(buildJsonObject {
                            put("type", "mrkdwn")
                            put("text", "*Repository:*\n`${alert.repoName}`")
                        })
                        add(buildJsonObject {
                            put("type", "mrkdwn")
                            put("text", "*Severity:*\n*${alert.severity}*")
                        })
                        add(buildJsonObject {
                            put("type", "mrkdwn")
                            put("text", "*Branch/Commit:*\n`${alert.branch ?: "default"} @ ${alert.commitSha?.take(7) ?: "HEAD"}`")
                        })
                        add(buildJsonObject {
                            put("type", "mrkdwn")
                            put("text", "*Total Violations:*\n`${alert.totalViolations}`")
                        })
                    }
                })
                add(buildJsonObject {
                    put("type", "section")
                    putJsonObject("text") {
                        put("type", "mrkdwn")
                        put("text", if (details.isBlank()) "_No architectural boundary violations detected._" else "*Violations Breakdown:*\n$details")
                    }
                })
                add(buildJsonObject {
                    put("type", "context")
                    putJsonArray("elements") {
                        add(buildJsonObject {
                            put("type", "mrkdwn")
                            put("text", "Evaluated at ${Instant.ofEpochMilli(alert.timestamp)} via RepoMind Drift Shield")
                        })
                    }
                })
            }
        }
        return json.encodeToString(root)
    }

    fun formatTeamsPayload(alert: DriftAlert): String {
        val color = if (alert.isBlocker) "d9534f" else "5cb85c"
        val title = if (alert.isBlocker) {
            "🚨 RepoMind Drift Alert: ${alert.totalViolations} Architectural Violation(s)"
        } else {
            "✅ RepoMind Drift Shield: Clean Architectural Check"
        }

        val details = alert.violations.take(8).joinToString("<br>") { v ->
            val lineStr = if (v.line > 0) ":${v.line}" else ""
            "• <b>[${v.rule}]</b> <code>${v.sourceFqn}$lineStr</code> → <code>${v.targetFqn}</code> [${v.edgeKind}]"
        }

        val root = buildJsonObject {
            put("@type", "MessageCard")
            put("@context", "https://schema.org/extensions")
            put("summary", title)
            put("themeColor", color)
            put("title", title)
            putJsonArray("sections") {
                add(buildJsonObject {
                    put("activityTitle", "Repository: ${alert.repoName}")
                    put("activitySubtitle", "Commit: ${alert.commitSha?.take(7) ?: "HEAD"} (${alert.branch ?: "main"}) | Author: ${alert.author ?: "Unknown"}")
                    putJsonArray("facts") {
                        add(buildJsonObject {
                            put("name", "Severity")
                            put("value", alert.severity)
                        })
                        add(buildJsonObject {
                            put("name", "Violations Count")
                            put("value", alert.totalViolations.toString())
                        })
                        add(buildJsonObject {
                            put("name", "Timestamp")
                            put("value", Instant.ofEpochMilli(alert.timestamp).toString())
                        })
                    }
                    put("text", if (details.isBlank()) "No architectural violations detected." else "<b>Offending Dependencies:</b><br>$details")
                })
            }
        }
        return json.encodeToString(root)
    }

    fun formatDiscordPayload(alert: DriftAlert): String {
        val color = if (alert.isBlocker) 15158332 else 3066993
        val details = alert.violations.take(8).joinToString("\n") { v ->
            val lineStr = if (v.line > 0) ":${v.line}" else ""
            "• `[${v.rule}]` `${v.sourceFqn}$lineStr` → `${v.targetFqn}` [${v.edgeKind}]"
        } + if (alert.violations.size > 8) "\n...and ${alert.violations.size - 8} more" else ""

        val root = buildJsonObject {
            putJsonArray("embeds") {
                add(buildJsonObject {
                    put("title", "🛡️ RepoMind Architectural Drift Shield")
                    put("color", color)
                    put("description", if (alert.isBlocker) {
                        "**${alert.totalViolations} architectural rule violation(s)** detected in repository `${alert.repoName}`."
                    } else {
                        "All architecture boundaries passed successfully in `${alert.repoName}`."
                    })
                    putJsonArray("fields") {
                        add(buildJsonObject {
                            put("name", "Repository")
                            put("value", "`${alert.repoName}`")
                            put("inline", true)
                        })
                        add(buildJsonObject {
                            put("name", "Severity")
                            put("value", "**${alert.severity}**")
                            put("inline", true)
                        })
                        add(buildJsonObject {
                            put("name", "Branch/Commit")
                            put("value", "`${alert.branch ?: "default"} @ ${alert.commitSha?.take(7) ?: "HEAD"}`")
                            put("inline", true)
                        })
                        add(buildJsonObject {
                            put("name", "Violations")
                            put("value", if (details.isBlank()) "None" else details)
                            put("inline", false)
                        })
                    }
                    putJsonObject("footer") {
                        put("text", "RepoMind Drift Shield • ${Instant.ofEpochMilli(alert.timestamp)}")
                    }
                })
            }
        }
        return json.encodeToString(root)
    }

    fun formatGenericPayload(alert: DriftAlert): String {
        return json.encodeToString(DriftAlert.serializer(), alert)
    }

    companion object {
        fun resolveGitContext(repoRoot: Path): GitContext {
            val repoName = repoRoot.fileName?.toString() ?: "unknown-repo"
            val branch = runGit(repoRoot, "rev-parse", "--abbrev-ref", "HEAD")
            val commit = runGit(repoRoot, "rev-parse", "HEAD")
            val author = runGit(repoRoot, "log", "-1", "--pretty=format:%an")
            return GitContext(
                repoName = repoName,
                branch = branch,
                commitSha = commit,
                author = author,
            )
        }

        private fun runGit(repoRoot: Path, vararg args: String): String? {
            return try {
                val pb = ProcessBuilder("git", *args)
                    .directory(repoRoot.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                val proc = pb.start()
                val ok = proc.waitFor(3, TimeUnit.SECONDS)
                if (ok && proc.exitValue() == 0) {
                    proc.inputStream.bufferedReader().readText().trim().ifBlank { null }
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class GitContext(
    val repoName: String,
    val branch: String?,
    val commitSha: String?,
    val author: String?,
)
