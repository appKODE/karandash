package io.karandash.plugins.outputs

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.finding.FormattedReport
import io.karandash.duration.KarandashDuration
import io.karandash.http.HttpClientSettings
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The run's `http.retry` reaches the webhook client, which the module builds per call outside any run services. */
class WebhookOutputRetryTest {
    private val report = FormattedReport(formatId = "json", body = "{}")
    private val config = WebhookOutputConfig(url = "https://hooks.example.com/in")

    private suspend fun requestsFor(
        status: HttpStatusCode,
        maxRetries: Int,
    ): Int {
        var requests = 0
        val module = WebhookOutputModule(
            MockEngine {
                requests++
                respond("no", status)
            }
        )
        module.initialize(TestPluginContext())
        val session = ModuleRegistryImpl().forRun()
        val retry = HttpClientSettings.Retry(maxRetries = maxRetries, baseDelay = KarandashDuration.ofMillis(1))
        session.provide(ProviderHttpClientFactory.HTTP_SETTINGS, HttpClientSettings(retry = retry))
        withContext(session.services) { assertFailsWith<IllegalStateException> { module.outputTyped(report, config) } }
        module.shutdown()
        return requests
    }

    @Test
    fun `maxRetries 0 sends exactly one request on a 500`() =
        runTest { assertEquals(1, requestsFor(HttpStatusCode.InternalServerError, 0)) }

    @Test
    fun `maxRetries 0 sends exactly one request on a 429`() = runTest { assertEquals(1, requestsFor(HttpStatusCode.TooManyRequests, 0)) }

    @Test
    fun `maxRetries 1 retries a 429 once`() = runTest { assertEquals(2, requestsFor(HttpStatusCode.TooManyRequests, 1)) }
}
