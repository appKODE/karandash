package io.karandash.platforms.gerrit

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import io.ktor.client.HttpClient
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Re-initializing with an unchanged config keeps the clients; a changed config swaps them; shutdown drops them
 *, closing the HTTP client keyed for the old credential.
 */
@OptIn(ExperimentalKarandashApi::class)
class GerritReinitTest {
    @Test
    fun reinitializeKeepsClientsUntilTheConfigChanges() =
        runTest {
            val service = GerritPlatformService()
            service.initialize(context("token-a"))
            val provider = assertNotNull(service.providerClient.value)
            val comment = assertNotNull(service.commentClient.value)
            val http = assertNotNull(service.commentClient.value).sharedHttpClient

            service.initialize(context("token-a"))
            assertSame(provider, service.providerClient.value, "unchanged config rebuilt the provider client")
            assertSame(comment, service.commentClient.value, "unchanged config rebuilt the comment client")

            service.initialize(context("token-b"))
            assertNotSame(provider, service.providerClient.value)
            assertNotSame(comment, service.commentClient.value)
            assertNotSame(
                http,
                assertNotNull(service.commentClient.value).sharedHttpClient,
                "a token change kept the HTTP client keyed for the old token"
            )
            assertFalse(http.isActive, "the replaced HTTP client was left open")

            service.shutdown()
            assertNull(service.providerClient.value)
            assertNull(service.commentClient.value)
        }

    @Test
    fun shutdownClosesTheSharedHttpClientOnce() =
        runTest {
            val service = GerritPlatformService()
            service.initialize(context("token-a"))
            val http = assertNotNull(service.commentClient.value).sharedHttpClient
            assertTrue(http.isActive)

            service.shutdown()
            assertFalse(http.isActive, "shutdown did not close the HTTP client")
            service.shutdown()
        }

    private fun context(token: String): PluginContext =
        object : PluginContext {
            override val pluginConfig = mapToJsonElement(mapOf("url" to "https://gerrit.example.com", "token" to token))
            override val hostVersion = "1.0.0-test"
            override val workingDirectory = "/tmp/test"

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}
        }
}
