@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.config.pipeline

import io.karandash.http.HttpClientLedger
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.manifest.ConfigSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

/** Without an explicit client, [RemoteConfigFetcher] builds its client through [ProviderHttpClientFactory]. */
class RemoteConfigFetcherClientTest {
    @Test
    fun `default client comes from the provider factory on the first remote fetch only`() =
        runTest {
            val ledger = HttpClientLedger()
            ProviderHttpClientFactory.installLedger(ledger)
            val fetcher = RemoteConfigFetcher(allowLocalhost = true)
            try {
                assertEquals(0, ledger.createdCount)
                // Port 1 refuses the connection at once; only the client creation matters here.
                runCatching { fetcher.fetch(ConfigSource(url = "http://127.0.0.1:1/config.yaml")) }
                runCatching { fetcher.fetch(ConfigSource(url = "http://127.0.0.1:1/config.yaml")) }
                assertEquals(1, ledger.createdCount)
            } finally {
                ProviderHttpClientFactory.clearLedger(ledger)
                fetcher.close()
            }
        }

    @Test
    fun `default client lets allowLocalhost reach a loopback host`() =
        runTest {
            val fetcher = RemoteConfigFetcher(allowLocalhost = true)
            try {
                // Port 1 refuses the connection; the request must get that far instead of being blocked as SSRF.
                val error = assertFails { fetcher.fetch(ConfigSource(url = "http://localhost:1/config.yaml")) }
                val messages = generateSequence(error) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
                assertFalse("SSRF" in messages, messages)
            } finally {
                fetcher.close()
            }
        }
}
