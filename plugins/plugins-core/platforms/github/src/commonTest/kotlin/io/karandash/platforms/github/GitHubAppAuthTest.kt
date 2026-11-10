package io.karandash.platforms.github

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import io.karandash.api.PluginContext
import io.karandash.crypto.CryptoProvider
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalEncodingApi::class)
class GitHubAppAuthTest {
    private val app = GitHubAppCredentials(appId = "4242", installationId = "777", privateKeyPem = pem)
    private val start = Instant.parse("2026-10-19T12:00:00Z")

    private class TestClock(
        var current: Instant,
    ) : Clock {
        override fun now(): Instant = current

        fun advance(by: Duration) {
            current += by
        }
    }

    /** Mock GitHub: issues `ghs_<n>` tokens valid one hour from [clock], answers 401 to any other bearer. */
    private class FakeGitHub(
        private val clock: TestClock,
        private val rejectFirstApiCall: Boolean = false,
    ) {
        val requests = mutableListOf<HttpRequestData>()
        private var issued = 0
        private var rejected = false

        val engine = MockEngine { request -> handle(request) }

        val exchanges: List<HttpRequestData> get() = requests.filter { it.url.encodedPath.endsWith("/access_tokens") }

        private fun MockRequestHandleScope.handle(request: HttpRequestData) =
            run {
                requests += request
                val auth = request.headers[HttpHeaders.Authorization].orEmpty()
                when {
                    request.url.encodedPath.endsWith("/access_tokens") -> {
                        issued++
                        val expires = clock.current + 1.hours
                        json("""{"token":"ghs_$issued","expires_at":"$expires"}""", HttpStatusCode.Created)
                    }

                    rejectFirstApiCall && !rejected -> {
                        rejected = true
                        json("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized)
                    }

                    auth == "Bearer ghs_$issued" -> {
                        json("""{"total_count":1,"repositories":[]}""", HttpStatusCode.OK)
                    }

                    else -> {
                        json("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized)
                    }
                }
            }

        private fun MockRequestHandleScope.json(
            body: String,
            status: HttpStatusCode,
        ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun client(
        fake: FakeGitHub,
        clock: TestClock,
        baseUrl: String = "https://api.github.com",
    ) = GitHubClient(
        baseUrl = baseUrl,
        httpClient = HttpClient(fake.engine) { install(ContentNegotiation) { json(KarandashJson.Default) } },
        responseCache = null,
        app = app,
        clock = clock,
    )

    @Test
    fun appJwtCarriesIssuedAtExpiryAndIssuerAndVerifiesWithTheAppKey() {
        val cache = GitHubAppTokenCache(app, TestClock(start)) { _, _ -> error("no exchange") }
        val parts = cache.appJwt(start).split(".")
        val claims = KarandashJson.Default.parseToJsonElement(base64Url.decode(parts[1]).decodeToString()).jsonObject
        assertEquals((start - 60.seconds).epochSeconds, claims.long("iat"))
        assertEquals((start + 9.minutes).epochSeconds, claims.long("exp"))
        assertEquals("4242", claims["iss"]?.jsonPrimitive?.content)
        val publicKeyDer = keyPair.publicKey.encodeToByteArrayBlocking(RSA.PublicKey.Format.DER)
        val signed = "${parts[0]}.${parts[1]}".encodeToByteArray()
        assertTrue(CryptoProvider.verifyRsaSha256Signature(signed, base64Url.decode(parts[2]), publicKeyDer))
    }

    @Test
    fun exchangesTheAppJwtOnceAndSendsTheInstallationTokenOnApiCalls() =
        runTest {
            val clock = TestClock(start)
            val fake = FakeGitHub(clock)
            val client = client(fake, clock)
            assertTrue(client.checkAuth("https://api.github.com/installation/repositories"))
            assertTrue(client.checkAuth("https://api.github.com/installation/repositories"))

            val exchange = fake.exchanges.single()
            assertEquals("POST", exchange.method.value)
            assertEquals("/app/installations/777/access_tokens", exchange.url.encodedPath)
            val jwt = exchange.headers[HttpHeaders.Authorization].orEmpty().removePrefix("Bearer ")
            assertEquals(3, jwt.split(".").size, "the exchange authenticates with the app JWT")
            val apiCalls = fake.requests - fake.exchanges.toSet()
            assertEquals(listOf("Bearer ghs_1", "Bearer ghs_1"), apiCalls.map { it.headers[HttpHeaders.Authorization] })
            client.close()
        }

    @Test
    fun refreshesTheInstallationTokenShortlyBeforeItExpires() =
        runTest {
            val clock = TestClock(start)
            val fake = FakeGitHub(clock)
            val client = client(fake, clock)
            assertTrue(client.checkAuth("https://api.github.com/installation/repositories"))
            clock.advance(50.minutes)
            assertTrue(client.checkAuth("https://api.github.com/installation/repositories"))
            assertEquals(1, fake.exchanges.size, "50 minutes in, the hour-long token is still used")
            clock.advance(6.minutes)
            assertTrue(client.checkAuth("https://api.github.com/installation/repositories"))
            assertEquals(2, fake.exchanges.size, "within 5 minutes of expires_at a new token is exchanged")
            assertEquals("Bearer ghs_2", fake.requests.last().headers[HttpHeaders.Authorization])
            client.close()
        }

    @Test
    fun a401DropsTheTokenAndRetriesOnceWithANewOne() =
        runTest {
            val clock = TestClock(start)
            val fake = FakeGitHub(clock, rejectFirstApiCall = true)
            val client = client(fake, clock)
            assertTrue(client.checkAuth("https://api.github.com/installation/repositories"))
            assertEquals(2, fake.exchanges.size)
            assertEquals("Bearer ghs_2", fake.requests.last().headers[HttpHeaders.Authorization])
            client.close()
        }

    @Test
    fun exchangesAgainstTheGitHubEnterpriseBaseUrl() =
        runTest {
            val clock = TestClock(start)
            val fake = FakeGitHub(clock)
            val client = client(fake, clock, baseUrl = "https://ghe.example.com/api/v3")
            assertTrue(client.checkAuth("https://ghe.example.com/api/v3/installation/repositories"))
            val exchange = fake.exchanges.single()
            assertEquals("ghe.example.com", exchange.url.host)
            assertEquals("/api/v3/app/installations/777/access_tokens", exchange.url.encodedPath)
            client.close()
        }

    @Test
    fun secretsStayOutOfToString() {
        assertFalse(app.toString().contains("PRIVATE KEY"))
        assertFalse(GitHubInstallationToken("ghs_secret", "2026-10-19T13:00:00Z").toString().contains("ghs_secret"))
    }

    @Test
    fun resolveReadsAppAuthWithoutAToken() {
        val creds = GitHubCredentials.resolve(appConfig("777"), { null }, pem)
        assertNull(creds.token)
        assertEquals(app, creds.app)
        assertEquals("https://api.github.com", creds.baseUrl)
    }

    @Test
    fun resolveRejectsMalformedAppAuth() {
        assertFailsWith<IllegalStateException> { GitHubCredentials.resolve(appConfig("not-a-number"), { null }, pem) }
        assertFailsWith<IllegalStateException> { GitHubCredentials.resolve(appConfig("777"), { null }, null) }
        val unknownType = buildJsonObject { putJsonObject("auth") { put("type", "oauth") } }
        assertFailsWith<IllegalStateException> { GitHubCredentials.resolve(unknownType, { null }, pem) }
    }

    @Test
    fun resolveNamesEachMissingAppField() {
        fun auth(vararg pairs: Pair<String, String>) = buildJsonObject { putJsonObject("auth") { pairs.forEach { (k, v) -> put(k, v) } } }
        val noAppId = assertFailsWith<IllegalStateException> { GitHubCredentials.resolve(auth("type" to "APP"), { null }, pem) }
        assertTrue(noAppId.message.orEmpty().contains("appId"))
        val blankAppId = auth("type" to "app", "appId" to " ", "installationId" to "1")
        assertFailsWith<IllegalStateException> { GitHubCredentials.resolve(blankAppId, { null }, pem) }
        val noInstallation = assertFailsWith<IllegalStateException> {
            GitHubCredentials.resolve(auth("type" to "app", "appId" to "1"), { null }, pem)
        }
        assertTrue(noInstallation.message.orEmpty().contains("installationId"))
        assertFailsWith<IllegalStateException> { GitHubCredentials.resolve(appConfig("777"), { null }, " ") }
        val tokenAuth = GitHubCredentials.resolve(auth("type" to "token"), { if (it == "GITHUB_TOKEN") "t" else null })
        assertEquals("t", tokenAuth.token)
        assertNull(tokenAuth.app)
    }

    @Test
    fun serviceInitializesAsTheAppWithAnInlineKeyAndChecksAuthAgainstTheInstallation() =
        runTest {
            val service = GitHubPlatformService()
            val warnings = mutableListOf<String>()
            val config = buildJsonObject {
                putJsonObject("auth") {
                    put("type", "app")
                    put("appId", "4242")
                    put("installationId", "777")
                    put("privateKey", pem)
                }
            }
            assertTrue(service.initialize(context(config, warnings)))
            assertTrue(warnings.isEmpty(), "warnings: $warnings")
            assertTrue(checkNotNull(service.client.value).isAppAuth)
            assertTrue(warnings.none { it.contains("PRIVATE KEY") })
            service.shutdown()
        }

    @Test
    fun serviceWarnsAndStaysUnconfiguredOnAnIncompleteAppConfig() =
        runTest {
            val service = GitHubPlatformService()
            val warnings = mutableListOf<String>()
            val config = buildJsonObject { putJsonObject("auth") { put("type", "app") } }
            assertFalse(service.initialize(context(config, warnings)))
            assertNull(service.client.value)
            assertNotNull(warnings.singleOrNull { it.contains("appId") })
            service.shutdown()
        }

    private fun appConfig(installationId: String): JsonObject =
        buildJsonObject {
            putJsonObject("auth") {
                put("type", "app")
                put("appId", "4242")
                put("installationId", installationId)
            }
        }

    private fun context(
        config: JsonObject,
        warnings: MutableList<String>,
    ) = object : PluginContext {
        override val pluginConfig = config
        override val hostVersion = "1.0.0-test"
        override val workingDirectory = "/tmp/test"

        override fun env(name: String): String? = null

        override fun log(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }
    }

    private fun JsonObject.long(key: String): Long = checkNotNull(this[key]).jsonPrimitive.long

    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    private companion object {
        /** One RSA key for the class: generating it per test costs seconds. */
        val keyPair = CryptographyProvider.Default
            .get(RSA.PKCS1)
            .keyPairGenerator(digest = SHA256)
            .generateKeyBlocking()
        val pem = keyPair.privateKey.encodeToByteArrayBlocking(RSA.PrivateKey.Format.PEM.PKCS1).decodeToString()
    }
}
