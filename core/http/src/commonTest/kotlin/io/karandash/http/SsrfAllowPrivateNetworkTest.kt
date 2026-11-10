package io.karandash.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `allow_private_network: true` permits private and dotless hosts only; everything else stays blocked. */
class SsrfAllowPrivateNetworkTest {
    private val blocked = listOf(
        "https://169.254.169.254/latest/meta-data",
        "https://169.254.1.1/x",
        "https://[fe80::1]/x",
        "https://127.0.0.1/x",
        "https://localhost/x",
        "https://[::1]/x",
        "https://metadata.google.internal/x",
        "https://2130706433/x",
    )
    private val privateHosts = listOf("https://10.1.2.3/x", "https://192.168.0.1/x", "https://[fd00::1]/x", "https://intranet/x")

    private fun client(addresses: List<String>) =
        HttpClient(MockEngine { respond("ok", HttpStatusCode.OK) }) {
            install(ssrfRedirectValidationPlugin { addresses })
        }

    @Test
    fun `flag on permits private literals and dotless hosts`() {
        for (url in privateHosts) {
            SsrfUrlValidator.validate(url, allowPrivateNetwork = true)
            SsrfUrlValidator.validateBaseUrl(url, allowPrivateNetwork = true)
        }
    }

    @Test
    fun `flag on still blocks metadata link-local loopback and plain http`() {
        for (url in blocked) {
            assertFailsWith<IllegalArgumentException>(url) { SsrfUrlValidator.validate(url, allowPrivateNetwork = true) }
            assertFailsWith<IllegalArgumentException>(url) { SsrfUrlValidator.validateBaseUrl(url, allowPrivateNetwork = true) }
        }
        assertFailsWith<IllegalArgumentException> { SsrfUrlValidator.validate("http://10.1.2.3/x", allowPrivateNetwork = true) }
        assertFailsWith<IllegalArgumentException> { SsrfUrlValidator.validateBaseUrl("http://10.1.2.3/x", allowPrivateNetwork = true) }
    }

    @Test
    fun `flag on lets a name resolving to a private address through but not loopback or metadata`() =
        runTest {
            withContext(NetworkPolicy(allowPrivateNetwork = true)) {
                client(listOf("10.0.0.5", "fd00::5")).get("https://git.example.corp/api")
                for (address in listOf("127.0.0.1", "169.254.169.254", "fe80::1%en0", "::ffff:127.0.0.1")) {
                    assertFailsWith<IllegalArgumentException>(address) { client(listOf(address)).get("https://git.example.corp/api") }
                }
            }
        }

    @Test
    fun `flag off keeps private hosts blocked with a hint to the flag`() =
        runTest {
            for (url in privateHosts.filterNot { it.contains("intranet") }) {
                val error = assertFailsWith<IllegalArgumentException>(url) { SsrfUrlValidator.validate(url) }
                assertTrue(error.message.orEmpty().contains("allow_private_network"), error.message)
            }
            assertFailsWith<IllegalArgumentException> { SsrfUrlValidator.validate("https://intranet/x") }
            val error = assertFailsWith<IllegalArgumentException> { client(listOf("10.0.0.5")).get("https://git.example.corp/api") }
            assertTrue(error.message.orEmpty().contains("allow_private_network"), error.message)
            val loopback = assertFailsWith<IllegalArgumentException> { client(listOf("127.0.0.1")).get("https://git.example.corp/api") }
            assertTrue(loopback.message.orEmpty().contains("allow_localhost"), loopback.message)
        }
}
