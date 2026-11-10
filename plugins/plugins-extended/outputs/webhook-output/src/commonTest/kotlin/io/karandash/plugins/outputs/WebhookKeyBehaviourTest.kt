package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** One behaviour test per `webhook` output key, set through the config JSON the pipeline hands the module. */
class WebhookKeyBehaviourTest {
    private val report = FormattedReport(formatId = "json", body = """{"ok":true}""")

    private suspend fun post(config: String): HttpRequestData {
        val seen = mutableListOf<HttpRequestData>()
        val module = WebhookOutputModule(
            MockEngine { request ->
                seen += request
                respond("ok")
            }
        )
        module.initialize(TestPluginContext())
        module.output(report, Json.parseToJsonElement(config))
        module.shutdown()
        return seen.single()
    }

    @Test
    fun `url is the endpoint the report is posted to`() =
        runTest {
            val request = post("""{"url": "https://hooks.example.com/in/abc"}""")

            assertEquals("https://hooks.example.com/in/abc", request.url.toString())
        }

    @Test
    fun `headers are sent on the request`() =
        runTest {
            val request = post("""{"url": "https://hooks.example.com/in", "headers": {"X-Team_Id": "42"}}""")

            assertEquals("42", request.headers["X-Team_Id"])
        }

    @Test
    fun `authToken is sent as a bearer authorization header`() =
        runTest {
            val request = post("""{"url": "https://hooks.example.com/in", "authToken": "s3cret"}""")
            val without = post("""{"url": "https://hooks.example.com/in"}""")

            assertEquals("Bearer s3cret", request.headers[HttpHeaders.Authorization])
            assertNull(without.headers[HttpHeaders.Authorization])
        }
}
