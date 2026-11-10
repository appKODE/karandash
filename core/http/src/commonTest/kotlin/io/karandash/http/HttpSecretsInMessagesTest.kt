@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.http

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.exception.ProviderApiException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * URLs in HTTP diagnostics, SSRF messages and 401/403 failures carry no
 * query tokens or webhook secrets; a 401/403 is a typed [ProviderApiException].
 */
class HttpSecretsInMessagesTest {
    private val secret = "glpat-SECRET123"

    @Test
    fun `the diagnostic listener gets the URL without its query`() =
        runTest {
            val urls = mutableListOf<String>()
            val errors = mutableListOf<String?>()
            val session = ModuleRegistryImpl().forRun()
            session.provide(
                ProviderHttpClientFactory.DIAGNOSTIC_LISTENER,
                HttpDiagnosticListener { _, url, _, _, _, error ->
                    urls += url
                    errors += error
                },
            )
            val engine = MockEngine { request ->
                if (request.url.encodedPath == "/fail") error("boom at ${request.url}")
                respond("ok", HttpStatusCode.OK)
            }
            val client = ProviderHttpClientFactory.create(engine = engine, services = session.services)
            client.get("https://gitlab.example.com/api/v4/projects?private_token=$secret&page=2")
            runCatchingFailure { client.get("https://gitlab.example.com/fail?private_token=$secret") }
            client.close()

            assertEquals(listOf("https://gitlab.example.com/api/v4/projects", "https://gitlab.example.com/fail"), urls.distinct())
            assertFalse(errors.any { secret in it.orEmpty() }, errors.toString())
        }

    @Test
    fun `SSRF messages hide query tokens and webhook paths`() {
        val base = assertFailsWith<IllegalArgumentException> {
            SsrfUrlValidator.validateUrlMatchesBase("https://evil.example.com/x?private_token=$secret", "https://gitlab.example.com")
        }
        assertFalse(secret in base.message.orEmpty(), base.message)
        val scheme = assertFailsWith<IllegalArgumentException> {
            SsrfUrlValidator.validateBaseUrl("ftp://gitlab.example.com/?access_token=$secret")
        }
        assertFalse(secret in scheme.message.orEmpty(), scheme.message)
    }

    @Test
    fun `the redirect guard message hides the webhook path`() =
        runTest {
            val engine = MockEngine { respond("ok", HttpStatusCode.OK) }
            val client = ProviderHttpClientFactory.create(engine = engine)
            val e = assertFailsWith<IllegalArgumentException> {
                client.get("https://169.254.169.254/webhooks/T000/$secret")
            }
            client.close()
            assertFalse(secret in e.message.orEmpty(), e.message)
            assertTrue("169.254.169.254" in e.message.orEmpty(), e.message)
        }

    @Test
    fun `a 401 is a typed ProviderApiException without the query token`() =
        runTest {
            val engine = MockEngine { respond("{}", HttpStatusCode.Unauthorized) }
            val client = ProviderHttpClientFactory.createProvider(providerName = "GitLab", engine = engine)
            val e = assertFailsWith<ProviderApiException> {
                client.get("https://gitlab.example.com/api/v4/user?private_token=$secret")
            }
            client.close()
            assertEquals(401, e.statusCode)
            assertTrue(e.isAuthError)
            assertTrue("Authentication failed for GitLab API" in e.message.orEmpty(), e.message)
            assertFalse(secret in e.message.orEmpty(), e.message)
        }

    private suspend fun runCatchingFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().startsWith("boom"), e.message)
        }
    }
}
