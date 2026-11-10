package io.karandash.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A public name that resolves to a non-public address is blocked before the request is sent. */
class SsrfDnsRebindingTest {
    private val sent = mutableListOf<String>()
    private val resolved = mutableListOf<String>()

    private fun client(
        addresses: List<String>,
        allowLocalhost: Boolean = false,
    ) = HttpClient(
        MockEngine { request ->
            sent += request.url.host
            respond("ok", HttpStatusCode.OK)
        },
    ) {
        install(
            ssrfRedirectValidationPlugin(allowLocalhost) { host ->
                resolved += host
                addresses
            },
        )
    }

    @Test
    fun `name resolving to a private address is blocked`() =
        runTest {
            val error = assertFailsWith<IllegalArgumentException> {
                client(listOf("93.184.216.34", "10.0.0.5")).get("https://rebind.example.com/api")
            }
            assertTrue(error.message.orEmpty().contains("10.0.0.5"), error.message)
            assertTrue(sent.isEmpty(), "request was sent")
        }

    @Test
    fun `name resolving to loopback or link-local or mapped loopback is blocked`() =
        runTest {
            for (address in listOf("127.0.0.1", "169.254.169.254", "fe80::1%en0", "::ffff:127.0.0.1", "fd00::1")) {
                assertFailsWith<IllegalArgumentException>(address) {
                    client(listOf(address)).get("https://rebind.example.com/api")
                }
            }
            assertTrue(sent.isEmpty())
        }

    @Test
    fun `name resolving only to public addresses passes`() =
        runTest {
            client(listOf("93.184.216.34", "2606:2800:220:1::1")).get("https://example.com/api")
            assertEquals(listOf("example.com"), sent)
        }

    @Test
    fun `unresolvable name passes to fail on its own DNS error`() =
        runTest {
            client(emptyList()).get("https://nowhere.example.com/api")
            assertEquals(listOf("nowhere.example.com"), sent)
        }

    @Test
    fun `allowLocalhost and IP literals skip resolution`() =
        runTest {
            client(listOf("10.0.0.5"), allowLocalhost = true).get("https://internal.example.com/api")
            client(listOf("10.0.0.5")).get("https://93.184.216.34/api")
            assertTrue(resolved.isEmpty(), "resolved $resolved")
            assertEquals(2, sent.size)
        }
}
