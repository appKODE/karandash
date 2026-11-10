package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertTrue

/** sec: the Slack webhook URL is a credential and never reaches a log line or error message. */
class SlackWebhookRedactionTest {
    private val report = FormattedReport(formatId = "slack", body = "{}")

    @Test
    fun failedPostDoesNotLogTheWebhook() =
        runTest {
            val context = TestPluginContext()
            val module = SlackOutputModule(MockEngine { request -> throw IOException("connect failed: ${request.url}") })
            module.initialize(context)

            module.outputTyped(report, SlackOutputConfig(webhook = "https://hooks.slack.com/services/T0/B0/$SECRET"))
            module.shutdown()

            val lines = context.logs + context.warnings
            assertTrue(context.warnings.any { "Failed to send Slack output" in it }, "no failure warning: $lines")
            assertTrue(lines.none { SECRET in it }, "webhook leaked: $lines")
        }

    @Test
    fun rejectedWebhookIsNotEchoed() =
        runTest {
            val context = TestPluginContext()
            val module = SlackOutputModule(MockEngine { error("must not be called") })
            module.initialize(context)

            module.outputTyped(report, SlackOutputConfig(webhook = "https://example.com/hook/$SECRET"))
            module.shutdown()
            assertTrue(context.warnings.single().startsWith("Failed to send Slack output"), "warnings: ${context.warnings}")
            assertTrue(context.warnings.none { SECRET in it }, "webhook echoed: ${context.warnings}")
        }

    private companion object {
        const val SECRET = "XyZsecretToken123"
    }
}
