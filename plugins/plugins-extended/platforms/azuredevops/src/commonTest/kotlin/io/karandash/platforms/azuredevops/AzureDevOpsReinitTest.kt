package io.karandash.platforms.azuredevops

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Re-initializing with an unchanged config keeps the clients; a changed config swaps them; shutdown drops them. */
@OptIn(ExperimentalKarandashApi::class)
class AzureDevOpsReinitTest {
    @Test
    fun reinitializeKeepsClientsUntilTheConfigChanges() =
        runTest {
            val service = AzureDevOpsPlatformService()
            service.initialize(context("token-a"))
            val provider = assertNotNull(service.providerClient.value)
            val comment = assertNotNull(service.commentClient.value)
            val http = assertNotNull(service.providerClient.value).sharedHttpClient
            assertSame(http, comment.sharedHttpClient, "the comment client does not share the provider HTTP client")

            service.initialize(context("token-a"))
            assertSame(provider, service.providerClient.value, "unchanged config rebuilt the provider client")
            assertSame(comment, service.commentClient.value, "unchanged config rebuilt the comment client")

            service.initialize(context("token-b"))
            assertNotSame(provider, service.providerClient.value)
            assertNotSame(comment, service.commentClient.value)
            assertNotSame(
                http,
                assertNotNull(service.providerClient.value).sharedHttpClient,
                "a token change kept the HTTP client keyed for the old token"
            )
            assertFalse(http.isActive, "the replaced HTTP client was left open")

            service.shutdown()
            assertNull(service.providerClient.value)
            assertNull(service.commentClient.value)
        }

    private fun context(token: String): PluginContext =
        object : PluginContext {
            override val pluginConfig = mapToJsonElement(mapOf("token" to token))
            override val hostVersion = "1.0.0-test"
            override val workingDirectory = "/tmp/test"

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}
        }
}
