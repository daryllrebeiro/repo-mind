package dev.repomind.core.rules.drift

import dev.repomind.core.rules.Violation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DriftShieldDispatcherTest {

    private val dispatcher = DriftShieldDispatcher()

    @Test
    fun `detectProvider identifies webhook providers from URL`() {
        assertEquals(WebhookProvider.SLACK, dispatcher.detectProvider("https://hooks.slack.com/services/T00/B00/XXXX"))
        assertEquals(WebhookProvider.TEAMS, dispatcher.detectProvider("https://mycompany.webhook.office.com/webhookb2/guid/IncomingWebhook/xxx"))
        assertEquals(WebhookProvider.TEAMS, dispatcher.detectProvider("https://prod-01.eastus.logic.azure.com/workflows/xxx"))
        assertEquals(WebhookProvider.DISCORD, dispatcher.detectProvider("https://discord.com/api/webhooks/12345/abcdef"))
        assertEquals(WebhookProvider.DISCORD, dispatcher.detectProvider("https://discordapp.com/api/webhooks/12345/abcdef"))
        assertEquals(WebhookProvider.GENERIC, dispatcher.detectProvider("https://api.internal.corp/alerts/drift"))
    }

    @Test
    fun `formatSlackPayload produces Block Kit JSON with violations`() {
        val alert = DriftAlert(
            repoName = "payment-service",
            commitSha = "abcdef1234567890",
            branch = "feature/checkout",
            author = "Architect Alice",
            violations = listOf(
                Violation(
                    rule = "controller-must-not-call-repo",
                    message = "Controllers must invoke Services, not Repositories",
                    sourceFqn = "com.sample.OrderController",
                    targetFqn = "com.sample.OrderRepository",
                    edgeKind = "CALLS",
                    line = 42,
                )
            ),
        )

        val payload = dispatcher.formatSlackPayload(alert)
        assertTrue(payload.contains("payment-service"))
        assertTrue(payload.contains("OrderController:42"))
        assertTrue(payload.contains("OrderRepository"))
        assertTrue(payload.contains("controller-must-not-call-repo"))
        assertTrue(payload.contains("HIGH"))
    }

    @Test
    fun `formatTeamsPayload produces MessageCard JSON`() {
        val alert = DriftAlert(
            repoName = "order-service",
            commitSha = "c0ffee123456",
            branch = "main",
            violations = listOf(
                Violation(
                    rule = "core-cannot-depend-on-cli",
                    message = "Core must be independent of presentation apps",
                    sourceFqn = "dev.repomind.core.model.Foo",
                    targetFqn = "dev.repomind.cli.Main",
                    edgeKind = "USES",
                    line = 10,
                )
            ),
        )

        val payload = dispatcher.formatTeamsPayload(alert)
        assertTrue(payload.contains("MessageCard"))
        assertTrue(payload.contains("order-service"))
        assertTrue(payload.contains("core-cannot-depend-on-cli"))
        assertTrue(payload.contains("d9534f")) // Red blocker theme color
    }

    @Test
    fun `formatDiscordPayload produces Embeds JSON`() {
        val alert = DriftAlert(
            repoName = "catalog-service",
            violations = emptyList(),
        )

        val payload = dispatcher.formatDiscordPayload(alert)
        assertTrue(payload.contains("catalog-service"))
        assertTrue(payload.contains("3066993")) // Green clean theme color
        assertTrue(payload.contains("CLEAN"))
    }

    @Test
    fun `formatGenericPayload serializes DriftAlert model`() {
        val alert = DriftAlert(
            repoName = "auth-service",
            commitSha = "1234567",
            violations = listOf(
                Violation(
                    rule = "no-cycles",
                    message = "Cycle detected",
                    sourceFqn = "com.auth.A",
                    targetFqn = "com.auth.B",
                    edgeKind = "CALLS",
                    line = 5,
                )
            ),
        )

        val payload = dispatcher.formatGenericPayload(alert)
        assertTrue(payload.contains("\"repoName\": \"auth-service\""))
        assertTrue(payload.contains("\"no-cycles\""))
    }

    @Test
    fun `dispatchAlert dry-run completes without network calls`() {
        val alert = DriftAlert(
            repoName = "test-repo",
            violations = listOf(
                Violation(
                    rule = "test-rule",
                    message = "test violation",
                    sourceFqn = "A",
                    targetFqn = "B",
                    edgeKind = "CALLS",
                    line = 1,
                )
            ),
        )

        val result = dispatcher.dispatchAlert(
            webhookUrl = "https://hooks.slack.com/services/mock/url",
            alert = alert,
            dryRun = true,
        )

        assertTrue(result.success)
        assertEquals(WebhookProvider.SLACK, result.provider)
        assertTrue(result.message.contains("Dry-run successful"))
        assertNotNull(result.payloadFormatted)
    }

    @Test
    fun `resolveGitContext returns non-empty repo name`(@TempDir tempDir: Path) {
        val ctx = DriftShieldDispatcher.resolveGitContext(tempDir)
        assertNotNull(ctx.repoName)
        assertTrue(ctx.repoName.isNotBlank())
    }
}
