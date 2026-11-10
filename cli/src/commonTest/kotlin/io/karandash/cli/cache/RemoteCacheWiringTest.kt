package io.karandash.cli.cache

import io.karandash.cache.CacheConfig
import io.karandash.cache.CacheManager
import io.karandash.cache.cas.CasDigest
import io.karandash.cache.remote.RemoteCacheBackendFactory
import io.karandash.cache.remote.RemoteCacheConfig
import io.karandash.cli.pipeline.PipelineInfrastructure
import io.karandash.cli.pipeline.RemoteCachePushScope
import io.karandash.crypto.AwsSigV4Request
import io.karandash.crypto.AwsSigV4Signer
import io.karandash.crypto.AwsStaticCredentials
import io.karandash.sdk.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * `cache.remote.auth` is sent as the Authorization header; an unsafe `cache.remote.url` is rejected.
 * An S3 backend with credentials signs with SigV4 instead.
 */
class RemoteCacheWiringTest {
    private class CapturingLogger : Logger {
        val messages = mutableListOf<String>()

        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            messages += message
        }

        override fun info(message: String) {
            messages += message
        }

        override fun warn(message: String) {
            messages += message
        }

        override fun error(message: String) {
            messages += message
        }
    }

    private suspend fun authorizationSent(auth: String?): String? {
        val sent = mutableListOf<String?>()
        val client = HttpClient(
            MockEngine { request ->
                sent += request.headers[HttpHeaders.Authorization]
                respond(ByteArray(0))
            }
        )
        KtorRemoteCacheFetcher(client, auth).request("GET", "https://cache.example.com/cas/ab/cd", null)
        return sent.single()
    }

    @Test
    fun `bare token is sent as a bearer token`() =
        runTest {
            assertEquals("Bearer s3cret", authorizationSent("s3cret"))
        }

    @Test
    fun `auth naming a scheme is sent verbatim`() =
        runTest {
            assertEquals("Basic dXNlcjpwdw==", authorizationSent("Basic dXNlcjpwdw=="))
        }

    @Test
    fun `no auth sends no header`() =
        runTest {
            assertNull(authorizationSent(null))
        }

    private suspend fun s3HeadersSent(config: RemoteCacheConfig): Map<String, String?> {
        val sent = mutableListOf<Map<String, String?>>()
        val client = HttpClient(
            MockEngine { request ->
                sent += SIGV4_HEADERS.associateWith { request.headers[it] } + ("url" to request.url.toString())
                respond(ByteArray(0), HttpStatusCode.Created)
            }
        )
        val backend = RemoteCacheBackendFactory.create(config, KtorRemoteCacheFetcher(client, config.auth), CapturingLogger())
        assertTrue(backend.push(CasDigest.of(BLOB), BLOB))
        return sent.single()
    }

    @Test
    fun `s3 backend with credentials signs each request with SigV4 instead of auth`() =
        runTest {
            val config = RemoteCacheConfig(
                backend = "s3",
                url = "https://bucket.s3.eu-west-1.amazonaws.com",
                auth = SECRET,
                region = "eu-west-1",
                accessKeyId = "AKIDEXAMPLE",
                secretAccessKey = "s3-secret",
                sessionToken = "s3-session",
            )
            val headers = s3HeadersSent(config)

            val authorization = checkNotNull(headers[HttpHeaders.Authorization])
            assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"), authorization)
            assertTrue(
                "/eu-west-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token," in authorization
            )
            assertEquals(CasDigest.of(BLOB).hex, headers["X-Amz-Content-Sha256"])
            assertEquals(AMZ_DATE_LENGTH, headers["X-Amz-Date"]?.length) // yyyyMMddTHHmmssZ
            assertEquals("s3-session", headers["X-Amz-Security-Token"])
        }

    @Test
    fun `s3 key prefix with special characters is sent encoded once and signed as sent`() =
        runTest {
            val sent = mutableListOf<Triple<String, String, Map<String, String?>>>()
            val client = HttpClient(
                MockEngine { request ->
                    sent += Triple(request.url.host, request.url.encodedPath, SIGV4_HEADERS.associateWith { request.headers[it] })
                    respond(ByteArray(0), HttpStatusCode.Created)
                }
            )
            val config = RemoteCacheConfig(
                backend = "s3",
                url = "https://bucket.s3.eu-west-1.amazonaws.com",
                prefix = "team cache/a+b=c%d/\u00fc~",
                region = "eu-west-1",
                accessKeyId = "AKIDEXAMPLE",
                secretAccessKey = "s3-secret",
            )
            val backend = RemoteCacheBackendFactory.create(config, KtorRemoteCacheFetcher(client, null), CapturingLogger())
            assertTrue(backend.push(CasDigest.of(BLOB), BLOB))

            val (host, path, headers) = sent.single()
            val hex = CasDigest.of(BLOB).hex
            assertEquals("/team%20cache/a%2Bb%3Dc%25d/%C3%BC~/cas/${hex.take(2)}/${hex.drop(2)}", path)
            val amzDate = checkNotNull(headers["X-Amz-Date"])
            val expected = AwsSigV4Signer.sign(
                AwsSigV4Request("PUT", host, path, "eu-west-1", "s3"),
                BLOB,
                AwsStaticCredentials("AKIDEXAMPLE", "s3-secret"),
                Instant.parse(
                    "${amzDate.take(4)}-${amzDate.substring(4, 6)}-${amzDate.substring(6, 8)}T" +
                        "${amzDate.substring(9, 11)}:${amzDate.substring(11, 13)}:${amzDate.substring(13, 15)}Z",
                ),
            )
            assertEquals(expected.authorization, headers[HttpHeaders.Authorization])
            assertTrue("%2525" !in path, "double-encoded: $path")
        }

    @Test
    fun `s3 backend without credentials sends anonymous requests`() =
        runTest {
            val headers = s3HeadersSent(RemoteCacheConfig(backend = "s3", url = "https://bucket.s3.amazonaws.com"))

            assertNull(headers[HttpHeaders.Authorization])
            assertNull(headers["X-Amz-Date"])
        }

    private suspend fun wire(
        url: String,
        allowLocalhost: Boolean = false,
    ): Triple<CacheManager, Boolean, CapturingLogger> {
        val logger = CapturingLogger()
        val manager = CacheManager(cacheDir = Path("build/tmp/remote-wiring"))
        val config = CacheConfig(remote = RemoteCacheConfig(backend = "http", url = url, auth = SECRET))
        val pushes = PipelineInfrastructure.wireRemoteCache(manager, config, logger, allowLocalhost)
        pushes?.drain()
        return Triple(manager, pushes != null, logger)
    }

    @Test
    fun `cache enabled false wires no remote layer`() =
        runTest {
            val manager = CacheManager(cacheDir = Path("build/tmp/remote-wiring-disabled"))
            val config = CacheConfig(enabled = false, remote = RemoteCacheConfig(backend = "http", url = "https://cache.example.com"))
            assertNull(PipelineInfrastructure.wireRemoteCache(manager, config, CapturingLogger()))
            assertNull(manager.remoteLayer)
        }

    @Test
    fun `remote cache url failing the SSRF check is rejected and the run stays local`() =
        runTest {
            listOf("http://cache.example.com", "https://169.254.169.254/latest", "https://localhost/cache").forEach { url ->
                val (manager, wired, logger) = wire(url)
                assertTrue(!wired, "remote layer wired for unsafe $url")
                assertNull(manager.remoteLayer)
                assertTrue(logger.messages.any { "cache.remote.url rejected" in it }, "no warning for $url: ${logger.messages}")
            }
        }

    @Test
    fun `loopback http remote cache url is wired under allow-localhost`() =
        runTest {
            val (manager, wired, logger) = wire("http://localhost:8080/cachebk", allowLocalhost = true)
            assertTrue(wired, "loopback remote not wired: ${logger.messages}")
            assertNotNull(manager.remoteLayer)

            val (strictManager, strictWired, _) = wire("http://localhost:8080/cachebk", allowLocalhost = false)
            assertFalse(strictWired, "loopback remote wired without allow-localhost")
            assertNull(strictManager.remoteLayer)
        }

    @Test
    fun `safe remote cache url is wired and auth never logged`() =
        runTest {
            val (manager, wired, logger) = wire("https://cache.example.com")
            assertTrue(wired)
            assertNotNull(manager.remoteLayer)
            assertTrue(logger.messages.none { SECRET in it }, "auth leaked: ${logger.messages}")
        }

    @Test
    fun `drain waits for in-flight pushes at most the bound - cancels the rest and closes the client`() =
        runTest {
            val client = HttpClient(MockEngine { respond(ByteArray(0)) })
            val pushes = RemoteCachePushScope(SupervisorJob(), 100.milliseconds, client)
            val pushScope = CoroutineScope(pushes.job + StandardTestDispatcher(testScheduler))
            val stalled = pushScope.launch { awaitCancellation() }
            val finished = pushScope.launch { delay(10.milliseconds) }

            pushes.drain()

            assertTrue(finished.isCompleted && !finished.isCancelled, "short push was not awaited")
            assertTrue(stalled.isCancelled, "stalled push outlived the drain bound")
            assertFalse(client.isActive, "remote client not closed")
        }

    private companion object {
        const val SECRET = "remote-cache-token"
        const val AMZ_DATE_LENGTH = 16
        val BLOB = "cache blob".encodeToByteArray()
        val SIGV4_HEADERS = listOf(HttpHeaders.Authorization, "X-Amz-Date", "X-Amz-Content-Sha256", "X-Amz-Security-Token")
    }
}
