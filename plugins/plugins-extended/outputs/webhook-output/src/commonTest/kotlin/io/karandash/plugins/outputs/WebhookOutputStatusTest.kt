package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A non-2xx webhook response is logged at warn and fails the output instead of passing silently. */
class WebhookOutputStatusTest {
    private val report = FormattedReport(formatId = "json", body = "{}")
    private val config = WebhookOutputConfig(url = "https://hooks.example.com/in")

    @Test
    fun errorStatusWarnsAndFails() =
        runTest {
            val context = TestPluginContext()
            val module = WebhookOutputModule(MockEngine { respond("boom", HttpStatusCode.InternalServerError) })
            module.initialize(context)

            val error = assertFailsWith<IllegalStateException> { module.outputTyped(report, config) }
            module.shutdown()

            assertTrue("HTTP 500" in error.message.orEmpty(), "error: ${error.message}")
            assertTrue(context.warnings.any { "HTTP 500" in it }, "warnings: ${context.warnings}")
        }

    @Test
    fun successStatusPasses() =
        runTest {
            val context = TestPluginContext()
            val module = WebhookOutputModule(MockEngine { respond("ok") })
            module.initialize(context)

            module.outputTyped(report, config)
            module.shutdown()

            assertTrue(context.warnings.isEmpty(), "warnings: ${context.warnings}")
        }

    @Test
    fun logsAndErrorsShowOnlySchemeAndHost() =
        runTest {
            val context = TestPluginContext()
            val module = WebhookOutputModule(MockEngine { respond("boom", HttpStatusCode.Forbidden) })
            module.initialize(context)
            val secretUrl = WebhookOutputConfig(url = "https://user:pw@hooks.example.com:8443/in/$SECRET?token=$SECRET")

            val error = assertFailsWith<IllegalStateException> { module.outputTyped(report, secretUrl) }
            module.shutdown()

            val lines = context.logs + context.warnings + error.message.orEmpty()
            assertTrue(lines.none { SECRET in it || "pw" in it }, "url leaked: $lines")
            assertTrue(context.logs.any { "https://hooks.example.com:8443" in it }, "host missing: ${context.logs}")
            assertTrue("https://hooks.example.com:8443 failed: HTTP 403" in error.message.orEmpty(), "error: ${error.message}")
        }

    private companion object {
        const val SECRET = "XyZsecretToken123"
    }
}
