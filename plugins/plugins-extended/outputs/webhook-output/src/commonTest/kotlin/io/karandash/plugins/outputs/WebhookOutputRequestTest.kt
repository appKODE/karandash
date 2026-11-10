package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebhookOutputRequestTest {
    private val report = FormattedReport(formatId = "json", body = """{"ok":true}""")

    @Test
    fun jsonConfigSendsBodyCustomHeadersAndBearerToken() =
        runTest {
            val seen = mutableListOf<String>()
            val module =
                WebhookOutputModule(
                    MockEngine { request ->
                        seen += request.url.toString()
                        seen += request.headers["X-Team_Id"].orEmpty()
                        seen += request.headers["Authorization"].orEmpty()
                        seen += request.body.toByteArray().decodeToString()
                        respond("ok")
                    },
                )
            module.initialize(TestPluginContext())
            val config =
                """{"url": "https://hooks.example.com/in", "headers": {"X-Team_Id": "42"}, "authToken": "s3cret"}"""

            module.output(report, Json.parseToJsonElement(config))
            module.shutdown()

            assertEquals(listOf("https://hooks.example.com/in", "42", "Bearer s3cret", report.body), seen)
        }

    @Test
    fun missingOrUnsafeUrlIsRejectedBeforeAnyRequest() =
        runTest {
            val module = WebhookOutputModule(MockEngine { error("must not be called") })
            module.initialize(TestPluginContext())

            val missing = assertFailsWith<IllegalArgumentException> { module.outputTyped(report, WebhookOutputConfig()) }
            assertEquals("Webhook output requires 'url' configuration", missing.message)
            assertFailsWith<IllegalArgumentException> { module.outputTyped(report, WebhookOutputConfig(url = "http://169.254.169.254/x")) }
            module.shutdown()
        }
}
