package io.karandash.http

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkPolicyTest {
    private val privateUrl = "https://10.0.0.5/api"
    private val client = ProviderHttpClientFactory.create(engine = MockEngine { respond("ok", HttpStatusCode.OK) })

    @Test
    fun `two runs with different allow private network settings do not affect each other`() =
        runTest {
            val allowSet = CompletableDeferred<Unit>()
            val denyChecked = CompletableDeferred<Unit>()
            val allowing = async(NetworkPolicy()) {
                NetworkPolicy.setAllowPrivateNetwork(true)
                allowSet.complete(Unit)
                denyChecked.await()
                client.get(privateUrl).status
            }
            val denying = async(NetworkPolicy()) {
                allowSet.await()
                assertFailsWith<IllegalArgumentException> { client.get(privateUrl) }.also { denyChecked.complete(Unit) }
            }

            assertEquals(HttpStatusCode.OK, allowing.await())
            assertTrue(
                denying
                    .await()
                    .message
                    .orEmpty()
                    .contains("allow_private_network")
            )
        }

    @Test
    fun `a scoped policy overrides the run value inside its block only`() =
        runTest {
            withContext(NetworkPolicy(allowPrivateNetwork = false)) {
                withContext(NetworkPolicy(allowPrivateNetwork = true)) {
                    assertEquals(HttpStatusCode.OK, client.get(privateUrl).status)
                }
                assertFalse(NetworkPolicy.allowsPrivateNetwork())
                assertFailsWith<IllegalArgumentException> { client.get(privateUrl) }
            }
        }

    @Test
    fun `outside a run private hosts are refused and setting the flag does nothing`() =
        runTest {
            NetworkPolicy.setAllowPrivateNetwork(true)
            assertFalse(NetworkPolicy.allowsPrivateNetwork())
            assertFailsWith<IllegalArgumentException> { client.get(privateUrl) }
        }
}
