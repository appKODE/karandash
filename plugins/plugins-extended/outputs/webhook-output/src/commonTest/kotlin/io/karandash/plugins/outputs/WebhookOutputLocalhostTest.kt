package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `allow-localhost` reaches the HTTP client too, not just the module's own URL check. */
class WebhookOutputLocalhostTest {
    private val report = FormattedReport(formatId = "json", body = """{"ok":true}""")
    private val config = WebhookOutputConfig(url = "http://127.0.0.1:8080/hook", authToken = "tkn")

    @Test
    fun loopbackUrlIsDeliveredWhenAllowLocalhost() =
        runTest {
            val seen = mutableListOf<Triple<String, String?, String>>()
            val module = WebhookOutputModule(
                MockEngine { request ->
                    seen +=
                        Triple(
                            request.url.toString(),
                            request.headers[HttpHeaders.Authorization],
                            request.body.toByteArray().decodeToString()
                        )
                    respond("ok")
                },
            )
            module.initialize(TestPluginContext(allowLocalhost = true))

            module.outputTyped(report, config)
            module.shutdown()

            assertEquals(Triple("http://127.0.0.1:8080/hook", "Bearer tkn", report.body), seen.single())
        }

    @Test
    fun loopbackUrlIsRejectedWithoutAllowLocalhost() =
        runTest {
            val module = WebhookOutputModule(MockEngine { respond("ok") })
            module.initialize(TestPluginContext())

            val error = assertFailsWith<IllegalArgumentException> { module.outputTyped(report, config) }
            module.shutdown()

            assertTrue("HTTPS" in error.message.orEmpty(), "error: ${error.message}")
        }
}
