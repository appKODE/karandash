package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** One behaviour test per `slack` output key. */
class SlackKeyBehaviourTest {
    @Test
    fun `webhook is the URL the payload is posted to`() =
        runTest {
            val urls = mutableListOf<String>()
            val module = SlackOutputModule(
                MockEngine { request ->
                    urls += request.url.toString()
                    respond("ok")
                }
            )
            module.initialize(TestPluginContext())

            module.outputTyped(
                FormattedReport(formatId = "slack", body = """{"blocks":[]}"""),
                SlackOutputConfig(webhook = "https://hooks.slack.com/services/T1/B2/key"),
            )
            module.shutdown()

            assertEquals(listOf("https://hooks.slack.com/services/T1/B2/key"), urls)
        }
}
