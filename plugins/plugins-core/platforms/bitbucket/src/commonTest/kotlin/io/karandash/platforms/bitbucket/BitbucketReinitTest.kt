package io.karandash.platforms.bitbucket

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Re-initializing with an unchanged config keeps the client; a changed token swaps it; shutdown drops it. */
class BitbucketReinitTest {
    @Test
    fun `reinitialize keeps the client until the token changes`() =
        runTest {
            val service = BitbucketPlatformService()
            assertTrue(service.initialize(context("token-a")))
            val first = assertNotNull(service.client.value)

            assertTrue(service.initialize(context("token-a")))
            assertSame(first, service.client.value, "unchanged config rebuilt the client")

            assertTrue(service.initialize(context("token-b")))
            assertNotSame(first, service.client.value)

            service.shutdown()
            assertNull(service.client.value)
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
