package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A loopback Slack webhook is accepted only under `allow-localhost`; anything else still needs hooks.slack.com. */
class SlackOutputLocalhostTest {
    private val report = FormattedReport(formatId = "slack", body = """{"blocks":[]}""")

    private suspend fun deliver(
        webhook: String,
        allowLocalhost: Boolean,
        context: TestPluginContext = TestPluginContext(allowLocalhost = allowLocalhost),
    ): List<String> {
        val urls = mutableListOf<String>()
        val module = SlackOutputModule(
            MockEngine { request ->
                urls += request.url.toString()
                respond("ok")
            },
        )
        module.initialize(context)
        try {
            module.outputTyped(report, SlackOutputConfig(webhook = webhook))
        } finally {
            module.shutdown()
        }
        return urls
    }

    @Test
    fun loopbackWebhookIsDeliveredWithAllowLocalhost() =
        runTest {
            assertEquals(listOf("http://localhost:9000/slack"), deliver("http://localhost:9000/slack", allowLocalhost = true))
        }

    @Test
    fun loopbackWebhookIsRejectedWithoutAllowLocalhost() =
        runTest {
            val context = TestPluginContext()
            assertEquals(emptyList(), deliver("http://localhost:9000/slack", allowLocalhost = false, context))
            assertTrue("hooks.slack.com" in context.warnings.single(), "warnings: ${context.warnings}")
        }

    @Test
    fun nonLoopbackNonSlackHostIsRejectedEvenWithAllowLocalhost() =
        runTest {
            assertEquals(emptyList(), deliver("https://evil.example.com/slack", allowLocalhost = true))
            assertEquals(emptyList(), deliver("http://10.0.0.5/slack", allowLocalhost = true))
        }

    /** the `allow-localhost` exception covers loopback only, never metadata or private hosts. */
    @Test
    fun `allow-localhost does not open metadata or private webhook hosts`() =
        runTest {
            assertEquals(emptyList(), deliver("http://169.254.169.254/latest/meta-data", allowLocalhost = true))
            assertEquals(emptyList(), deliver("http://192.168.1.10/slack", allowLocalhost = true))
        }
}
