package io.karandash.cli.plugin

import io.karandash.api.exception.RegistrySecurityException
import io.karandash.manifest.ManifestData
import io.karandash.registry.RegistryAuth
import io.karandash.registry.RegistryClient
import io.karandash.testfixtures.TestFiles
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegistryClientAuthTest {
    private val registryJson =
        """{"version":"1","lastUpdated":"2026-02-10T00:00:00Z","plugins":[]}"""

    private fun clientWithCapture(
        auth: RegistryAuth? = null,
        capturedHeaders: MutableMap<String, String>,
        cacheDir: Path = TestFiles.createTestTempDirectory("karandash-auth-test"),
    ): RegistryClient {
        val mockEngine = MockEngine { request ->
            request.headers.forEach { key, values ->
                capturedHeaders[key] = values.firstOrNull() ?: ""
            }
            respond(
                content = ByteReadChannel(registryJson),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return RegistryClient(
            cacheDir = cacheDir,
            registryUrl = "https://example.com/registry.json",
            auth = auth,
            httpClient = HttpClient(mockEngine),
        )
    }

    @Test
    fun no_auth_header_sent_when_auth_is_null() =
        runTest {
            val captured = mutableMapOf<String, String>()
            val client = clientWithCapture(auth = null, capturedHeaders = captured)

            client.fetchRegistry(forceRefresh = true)

            assertFalse(captured.containsKey("Authorization"), "No Authorization header expected")
            assertFalse(captured.containsKey("PRIVATE-TOKEN"), "No PRIVATE-TOKEN header expected")
        }

    @Test
    fun bearer_token_sent_with_default_authorization_header() =
        runTest {
            val captured = mutableMapOf<String, String>()
            val auth = RegistryAuth(token = "my-token")
            val client = clientWithCapture(auth = auth, capturedHeaders = captured)

            client.fetchRegistry(forceRefresh = true)

            assertTrue(captured.containsKey("Authorization"), "Authorization header should be present")
            assertEquals("Bearer my-token", captured["Authorization"])
        }

    @Test
    fun gitlab_private_token_sent_without_bearer_prefix() =
        runTest {
            val captured = mutableMapOf<String, String>()
            val auth = RegistryAuth(token = "glpat-abc123", headerName = RegistryAuth.GITLAB_HEADER_NAME)
            val client = clientWithCapture(auth = auth, capturedHeaders = captured)

            client.fetchRegistry(forceRefresh = true)

            assertTrue(captured.containsKey("PRIVATE-TOKEN"), "PRIVATE-TOKEN header should be present")
            assertEquals("glpat-abc123", captured["PRIVATE-TOKEN"])
            assertFalse(captured.containsKey("Authorization"), "No Authorization header expected for GitLab auth")
        }

    @Test
    fun http_url_rejected_regardless_of_auth() =
        runTest {
            val client = RegistryClient(
                cacheDir = TestFiles.createTestTempDirectory("karandash-auth-security-test"),
                registryUrl = "http://insecure.example.com/registry.json",
                auth = RegistryAuth(token = "some-token"),
            )

            val result = client.fetchRegistry(forceRefresh = true)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is RegistrySecurityException)
        }

    @Test
    fun `registry token is not forwarded on a redirect to another host`() =
        runTest {
            val seen = mutableMapOf<String, String?>()
            val engine = MockEngine { request ->
                seen[request.url.host] = request.headers[RegistryAuth.GITLAB_HEADER_NAME]
                if (request.url.host == "registry.example.com") {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.example.net/registry.json"))
                } else {
                    respond(registryJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
            val manifest = ManifestData(
                registryUrl = "https://registry.example.com/registry.json",
                registryToken = "reg-secret",
                registryAuthHeader = RegistryAuth.GITLAB_HEADER_NAME,
            )

            manifest
                .registryClient(
                    cacheDir = TestFiles.createTestTempDirectory("karandash-auth-redirect"),
                    engine = engine
                ).fetchRegistry(forceRefresh = true)

            assertEquals("reg-secret", seen["registry.example.com"])
            assertTrue(seen.containsKey("cdn.example.net"), "redirect should be followed")
            assertEquals(null, seen["cdn.example.net"])
        }
}
