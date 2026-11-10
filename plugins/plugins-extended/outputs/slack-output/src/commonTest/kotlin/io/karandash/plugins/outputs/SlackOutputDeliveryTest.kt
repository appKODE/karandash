package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The channel is applied by SlackOutputModule: `slack.channel` reaches the payload; a non-2xx webhook response is a warned failure, not a success. */
class SlackOutputDeliveryTest {
    private val webhook = "https://hooks.slack.com/services/T0/B0/x"
    private val report = FormattedReport(formatId = "slack", body = """{"blocks":[]}""")

    private fun recordingModule(bodies: MutableList<String>) =
        SlackOutputModule(
            MockEngine { request ->
                bodies += request.body.toByteArray().decodeToString()
                respond("ok")
            },
        )

    @Test
    fun `the slack channel reaches the payload`() =
        runTest {
            val bodies = mutableListOf<String>()
            val module = recordingModule(bodies)
            module.initialize(TestPluginContext())

            module.outputTyped(report, SlackOutputConfig(webhook = webhook, channel = "#x"))
            module.shutdown()

            assertTrue(""""channel":"#x"""" in bodies.single(), "channel missing: $bodies")
        }

    @Test
    fun noChannelKeepsTheBodyUnchanged() =
        runTest {
            val bodies = mutableListOf<String>()
            val module = recordingModule(bodies)
            module.initialize(TestPluginContext())

            module.outputTyped(report, SlackOutputConfig(webhook = webhook))
            module.shutdown()

            assertEquals(report.body, bodies.single())
        }

    @Test
    fun errorStatusIsWarnedNotReportedAsSent() =
        runTest {
            val context = TestPluginContext()
            val module = SlackOutputModule(MockEngine { respond("no_service", HttpStatusCode.NotFound) })
            module.initialize(context)

            module.outputTyped(report, SlackOutputConfig(webhook = webhook))
            module.shutdown()

            assertTrue(context.warnings.any { "HTTP 404" in it && "no_service" in it }, "warnings: ${context.warnings}")
            assertTrue(context.logs.none { "sent successfully" in it }, "logs: ${context.logs}")
        }

    @Test
    fun channelIsSkippedWhenTheBodyCannotCarryIt() =
        runTest {
            val bodies = mutableListOf<String>()
            val context = TestPluginContext()
            val module = recordingModule(bodies)
            module.initialize(context)
            val bodiesIn = listOf("not json", "[1]", """{"channel":"#own"}""")

            for (body in bodiesIn) {
                module.output(
                    FormattedReport(formatId = "slack", body = body),
                    Json.parseToJsonElement("""{"webhook": "$webhook", "channel": "#x"}"""),
                )
            }
            module.outputTyped(report, SlackOutputConfig(webhook = webhook, channel = " "))
            module.shutdown()

            assertEquals(bodiesIn + report.body, bodies)
            assertEquals(2, context.warnings.size, "warnings: ${context.warnings}")
            assertTrue(context.warnings[0].startsWith("Slack channel '#x' not applied: body is not JSON"))
            assertTrue(context.warnings[1].startsWith("Slack channel '#x' not applied: body is not a JSON object"))
        }

    @Test
    fun missingWebhookOrClientIsReported() =
        runTest {
            val module = recordingModule(mutableListOf())
            val context = TestPluginContext()
            module.initialize(context)
            module.outputTyped(report, SlackOutputConfig())
            module.shutdown()
            module.outputTyped(report, SlackOutputConfig(webhook = webhook))
            assertEquals(
                listOf(
                    "Failed to send Slack output: Slack output requires 'webhook' configuration",
                    "Failed to send Slack output: HTTP client not initialized",
                ),
                context.warnings,
            )
        }

    @Test
    fun malformedLoopbackUrlIsRejectedWithAWarning() =
        runTest {
            val context = TestPluginContext(allowLocalhost = true)
            val module = recordingModule(mutableListOf())
            module.initialize(context)

            module.outputTyped(report, SlackOutputConfig(webhook = "ftp://localhost/x"))
            module.shutdown()

            assertEquals(2, context.warnings.size, "warnings: ${context.warnings}")
            assertTrue(context.warnings[0].startsWith("Slack webhook is not a valid URL"), "warnings: ${context.warnings}")
            assertTrue(
                context.warnings[1].startsWith("Failed to send Slack output: Slack webhook must be"),
                "warnings: ${context.warnings}"
            )
        }
}
