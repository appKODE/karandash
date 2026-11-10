package io.karandash.platforms.github

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.MockRoute
import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.pathEndsWith
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each GitHub platform key changes the request the service sends: host, path or Authorization header. */
@OptIn(ExperimentalEncodingApi::class)
class GitHubKeyBehaviourTest {
    private val tokenRoute = pathEndsWith("/access_tokens", """{"token":"ghs_app","expires_at":"2099-01-01T00:00:00Z"}""")

    private suspend fun firstRequest(
        config: Map<String, String>,
        routes: List<MockRoute> = emptyList(),
    ): io.ktor.client.request.HttpRequestData {
        val server = RecordingMockServer(routes)
        val service = GitHubPlatformService(server.engine)
        service.initialize(TestPluginContext(JsonObject(config.mapValues { JsonPrimitive(it.value) })))
        runCatching { service.findExistingComment("o/r", "7", "marker") }
        return server.requests().first()
    }

    @Test
    fun `url sends API requests to the configured host`() =
        runTest {
            val request = firstRequest(mapOf("token" to "t", "url" to "https://ghe.example.com/api/v3"))
            assertEquals("ghe.example.com", request.url.host)
            assertTrue(request.url.encodedPath.startsWith("/api/v3/repos/o/r"), request.url.encodedPath)
        }

    @Test
    fun `baseUrl alias sends API requests to the configured host`() =
        runTest {
            val request = firstRequest(mapOf("token" to "t", "baseUrl" to "https://alias.example.com/api/v3"))
            assertEquals("alias.example.com", request.url.host)
        }

    @Test
    fun `token is sent as the bearer credential`() =
        runTest {
            val request = firstRequest(mapOf("token" to "secret-pat"))
            assertEquals("api.github.com", request.url.host)
            assertEquals("Bearer secret-pat", request.headers[HttpHeaders.Authorization])
        }

    @Test
    fun `auth type app exchanges a JWT for an installation token and drops the configured token`() =
        runTest {
            val server = RecordingMockServer(listOf(tokenRoute))
            val service = GitHubPlatformService(server.engine)
            service.initialize(TestPluginContext(appConfig()))
            runCatching { service.findExistingComment("o/r", "7", "marker") }
            val requests = server.requests()
            val api = requests.first { !it.url.encodedPath.endsWith("/access_tokens") }
            assertEquals("Bearer ghs_app", api.headers[HttpHeaders.Authorization])
            assertTrue(requests.none { it.headers[HttpHeaders.Authorization] == "Bearer ignored-pat" })
        }

    @Test
    fun `auth installationId names the installation the token is requested for`() =
        runTest {
            val exchange = exchange()
            assertEquals(HttpMethod.Post, exchange.method)
            assertEquals("/app/installations/9090/access_tokens", exchange.url.encodedPath)
        }

    @Test
    fun `auth appId is the issuer of the app JWT`() =
        runTest {
            val jwt = checkNotNull(exchange().headers[HttpHeaders.Authorization]).removePrefix("Bearer ")
            val claims = Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT)
                .decode(jwt.split(".")[1])
                .decodeToString()
            val issuer = KarandashJson.Default
                .parseToJsonElement(claims)
                .jsonObject["iss"]
                ?.jsonPrimitive
                ?.content
            assertEquals("4242", issuer)
        }

    @Test
    fun `auth privateKey is required for app auth`() =
        runTest {
            val service = GitHubPlatformService(RecordingMockServer(emptyList()).engine)
            val context = TestPluginContext(appConfig(withKey = false))
            assertEquals(false, service.initialize(context))
            assertTrue(context.warnings.any { "privateKey" in it }, context.warnings.toString())
            assertNull(service.client.value)
        }

    @Test
    fun `auth privateKey signs the app JWT`() =
        runTest {
            val jwt = checkNotNull(exchange().headers[HttpHeaders.Authorization]).removePrefix("Bearer ")
            assertEquals(3, jwt.split(".").size)
            assertTrue(jwt.split(".")[2].isNotEmpty())
        }

    @Test
    fun `auth type token keeps the configured token`() =
        runTest {
            val config = JsonObject(
                mapOf(
                    "token" to JsonPrimitive("pat"),
                    "auth" to JsonObject(mapOf("type" to JsonPrimitive("token"))),
                ),
            )
            val creds = GitHubCredentials.resolve(config, { null })
            assertEquals("pat", creds.token)
            assertNull(creds.app)
            assertFailsWith<IllegalStateException> {
                GitHubCredentials.resolve(JsonObject(mapOf("auth" to JsonObject(mapOf("type" to JsonPrimitive("oauth"))))), { null })
            }
        }

    private suspend fun exchange(): io.ktor.client.request.HttpRequestData {
        val server = RecordingMockServer(listOf(tokenRoute))
        val service = GitHubPlatformService(server.engine)
        service.initialize(TestPluginContext(appConfig()))
        runCatching { service.findExistingComment("o/r", "7", "marker") }
        return server.requests().first { it.url.encodedPath.endsWith("/access_tokens") }
    }

    private fun appConfig(withKey: Boolean = true): JsonObject {
        val auth = buildMap {
            put("type", JsonPrimitive("app"))
            put("appId", JsonPrimitive("4242"))
            put("installationId", JsonPrimitive("9090"))
            if (withKey) put("privateKey", JsonPrimitive(pem))
        }
        return JsonObject(mapOf("token" to JsonPrimitive("ignored-pat"), "auth" to JsonObject(auth)))
    }

    private companion object {
        val pem = CryptographyProvider.Default
            .get(RSA.PKCS1)
            .keyPairGenerator(digest = SHA256)
            .generateKeyBlocking()
            .privateKey
            .encodeToByteArrayBlocking(RSA.PrivateKey.Format.PEM.PKCS1)
            .decodeToString()
    }
}
