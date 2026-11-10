@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.http

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.run.RunScopedServices
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The diagnostic listener was one global on [ProviderHttpClientFactory], so two collectors in one
 * process recorded each other's requests, and the run-scoped `DIAGNOSTIC_LISTENER` key was never provided.
 */
class RunScopedDiagnosticListenerTest {
    @Test
    fun `two runs sharing a client each record only their own requests`() =
        runTest {
            val seenA = mutableListOf<String>()
            val seenB = mutableListOf<String>()
            val runA = servicesWith(HttpDiagnosticListener { _, url, _, _, _, _ -> seenA += url })
            val runB = servicesWith(HttpDiagnosticListener { _, url, _, _, _, _ -> seenB += url })
            // built once, outside any run, like a module client built in initialize()
            val client = ProviderHttpClientFactory.create(engine = MockEngine { respondOk() })

            withContext(runA) { client.get("https://example.com/a") }
            withContext(runB) { client.get("https://example.com/b") }
            client.get("https://example.com/none")
            client.close()

            assertEquals(listOf("https://example.com/a"), seenA)
            assertEquals(listOf("https://example.com/b"), seenB)
        }

    @Test
    fun `a client created with run services keeps the listener of that run`() =
        runTest {
            val seen = mutableListOf<String>()
            val run = servicesWith(HttpDiagnosticListener { _, url, _, _, _, _ -> seen += url })
            val client = ProviderHttpClientFactory.create(engine = MockEngine { respondOk() }, services = run)

            client.get("https://example.com/x")
            client.close()

            assertEquals(listOf("https://example.com/x"), seen)
        }

    private suspend fun servicesWith(listener: HttpDiagnosticListener): RunScopedServices {
        val session = ModuleRegistryImpl().forRun()
        session.provide(ProviderHttpClientFactory.DIAGNOSTIC_LISTENER, listener)
        return session.services
    }
}
