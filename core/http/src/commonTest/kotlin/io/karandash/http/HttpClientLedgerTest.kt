@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.http

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.run.RunScopedServices
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HttpClientLedgerTest {
    @Test
    fun `Ktor close completes the client Job`() =
        runTest {
            val client = ProviderHttpClientFactory.create(engine = MockEngine { respondOk() })
            client.get("https://example.com/")
            val job = assertNotNull(client.coroutineContext[Job])
            client.close()
            job.join()
            assertTrue(job.isCompleted)
        }

    @Test
    fun `ledger from run services counts created and closed clients`() =
        runTest {
            val ledger = HttpClientLedger()
            val services = servicesWith(ledger)
            val first = ProviderHttpClientFactory.create(engine = MockEngine { respondOk() }, services = services)
            val second = ProviderHttpClientFactory.create(engine = MockEngine { respondOk() }, services = services)
            assertEquals(2, ledger.createdCount)
            assertEquals(0, ledger.closedCount)

            first.close()
            second.close()
            first.coroutineContext[Job]?.join()
            second.coroutineContext[Job]?.join()
            assertEquals(ledger.createdCount, ledger.closedCount)
        }

    @Test
    fun `run ledgers do not see each other's clients`() =
        runTest {
            val a = HttpClientLedger()
            val b = HttpClientLedger()
            val clientA = ProviderHttpClientFactory.create(engine = MockEngine { respondOk() }, services = servicesWith(a))
            assertEquals(1, a.createdCount)
            assertEquals(0, b.createdCount)
            clientA.close()
        }

    @Test
    fun `stale clearLedger does not clear a newer run's ledger`() =
        runTest {
            val old = HttpClientLedger()
            val current = HttpClientLedger()
            ProviderHttpClientFactory.installLedger(old)
            ProviderHttpClientFactory.installLedger(current)
            ProviderHttpClientFactory.clearLedger(old)

            val client = ProviderHttpClientFactory.create(engine = MockEngine { respondOk() })
            ProviderHttpClientFactory.clearLedger(current)
            client.close()
            assertEquals(1, current.createdCount)
            assertEquals(0, old.createdCount)
        }

    private suspend fun servicesWith(ledger: HttpClientLedger): RunScopedServices {
        val session = ModuleRegistryImpl().forRun()
        session.provide(ProviderHttpClientFactory.CLIENT_LEDGER, ledger)
        return session.services
    }
}
