package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.http.HttpAuth
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The webhook auth token and custom headers are host-scoped credentials, stripped from any
 * request that leaves the webhook host; the content type is configurable).
 */
class WebhookOutputAuthScopeTest {
    @Test
    fun `auth token and headers are keyed for the webhook host only`() {
        val config = WebhookOutputConfig(url = "https://hooks.example.com/in", headers = mapOf("X-Key" to "k"), authToken = "s3cret")
        val creds = webhookCredentials(config)
        assertEquals(HttpAuth.Bearer("s3cret"), creds.authFor("https://hooks.example.com/other"))
        assertNull(creds.authFor("https://attacker.example.net/in"))
        assertTrue(creds.headerNames().containsAll(setOf(HttpHeaders.Authorization, "X-Key")), creds.headerNames().toString())
    }

    @Test
    fun `content type defaults to json and is configurable`() =
        runTest {
            val seen = mutableListOf<String?>()
            val module = WebhookOutputModule(
                MockEngine { request ->
                    seen += request.body.contentType?.toString()
                    seen += request.headers[HttpHeaders.Authorization]
                    respond("ok")
                },
            )
            module.initialize(TestPluginContext())
            val report = FormattedReport(formatId = "markdown", body = "# ok")
            module.outputTyped(report, WebhookOutputConfig(url = "https://hooks.example.com/in", authToken = "t"))
            module.outputTyped(report, WebhookOutputConfig(url = "https://hooks.example.com/in", contentType = "text/markdown"))
            module.shutdown()
            assertEquals(listOf("application/json", "Bearer t", "text/markdown; charset=UTF-8", null), seen)
        }
}
