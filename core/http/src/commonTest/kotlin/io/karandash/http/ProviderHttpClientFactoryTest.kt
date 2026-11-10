package io.karandash.http

import io.karandash.api.exception.ProviderApiException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class ProviderHttpClientFactoryTest {
    @Test
    fun `followRedirects false returns the redirect answer instead of following it`() =
        runTest {
            val requested = mutableListOf<String>()
            val engine = MockEngine { request ->
                requested += request.url.encodedPath
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.example.com/next"))
            }
            val client = ProviderHttpClientFactory.create(engine = engine, followRedirects = false)

            assertEquals(HttpStatusCode.Found, client.get("https://example.com/start").status)
            assertEquals(listOf("/start"), requested)
            client.close()
        }

    @Test
    fun `createProvider maps 401 to auth failure`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = """{"message":"401 Unauthorized"}""",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val client = ProviderHttpClientFactory.createProvider(providerName = "GitLab", engine = mockEngine)

            val exception = assertFailsWith<ProviderApiException> {
                client.get("https://gitlab.example.com/api/v4/projects/group%2Frepo/merge_requests/1").bodyAsText()
            }
            assertEquals(exception.message?.contains("Authentication failed for GitLab API"), true)
            assertEquals(exception.message?.contains("401"), true)

            client.close()
        }

    @Test
    fun `createProvider maps 403 to access denied failure`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = """{"message":"403 Forbidden"}""",
                    status = HttpStatusCode.Forbidden,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val client = ProviderHttpClientFactory.createProvider(providerName = "GitHub", engine = mockEngine)

            val exception = assertFailsWith<ProviderApiException> {
                client.get("https://api.github.com/repos/org/repo/pulls/1").bodyAsText()
            }
            assertEquals(exception.message?.isNotBlank(), true)

            client.close()
        }

    @Test
    fun `createProvider keeps non-auth status as ClientRequestException`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = """{"message":"Not found"}""",
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val client = ProviderHttpClientFactory.createProvider(providerName = "Bitbucket", engine = mockEngine)

            assertFailsWith<ClientRequestException> {
                client.get("https://api.bitbucket.org/2.0/repositories/workspace/repo/pullrequests/404").bodyAsText()
            }

            client.close()
        }

    @Test
    fun `create blocks redirect to SSRF target`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://169.254.169.254/latest/meta-data/")
                )
            }

            val client = ProviderHttpClientFactory.create(engine = mockEngine)

            val exception = assertFailsWith<IllegalArgumentException> {
                client.get("https://safe.example.com/api").bodyAsText()
            }
            assertEquals(exception.message?.contains("169.254.169.254"), true)
            assertEquals(exception.message?.contains("blocked"), true)

            client.close()
        }

    @Test
    fun `createProvider reaches localhost only when allowLocalhost is set`() =
        runTest {
            val mockEngine = MockEngine { respond("ok") }

            val blocked = ProviderHttpClientFactory.createProvider("Jira", engine = mockEngine)
            assertFails { blocked.get("http://localhost:8080/rest/api/3/issue/ABC-1").bodyAsText() }
            blocked.close()

            val allowed = ProviderHttpClientFactory.createProvider("Jira", engine = mockEngine, allowLocalhost = true)
            assertEquals("ok", allowed.get("http://localhost:8080/rest/api/3/issue/ABC-1").bodyAsText())
            allowed.close()
        }
}
