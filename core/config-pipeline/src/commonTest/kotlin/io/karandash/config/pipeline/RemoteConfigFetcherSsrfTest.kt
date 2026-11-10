package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.http.HttpAuth
import io.karandash.http.NetworkPolicy
import io.karandash.logger.Logger
import io.karandash.manifest.ConfigSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** SSRF host policy, plain-HTTP warnings, redirect refusal and body cap of [RemoteConfigFetcher]. */
class RemoteConfigFetcherSsrfTest {
    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private val requested = mutableListOf<String>()
    private val logger = RecordingLogger()

    private fun fetcher(
        mode: SsrfMode,
        allowLocalhost: Boolean = false,
        respond: suspend io.ktor.client.engine.mock.MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData = {
            respond("key: value", HttpStatusCode.OK)
        },
    ): RemoteConfigFetcher {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requested += request.url.toString()
                    respond()
                }
            }
        }
        return RemoteConfigFetcher(client, mode, allowLocalhost, logger)
    }

    private suspend fun RemoteConfigFetcher.rejects(url: String): ConfigException =
        assertFailsWith<ConfigException>(url) { fetch(ConfigSource(url = url)) }

    @Test
    fun strictRejectsPrivateLoopbackAndMetadataBeforeAnyRequest() =
        runTest {
            val strict = fetcher(SsrfMode.STRICT)
            listOf(
                "https://10.0.0.5/x.yaml",
                "https://192.168.1.2/x.yaml",
                "https://intranet/x.yaml",
                "https://127.0.0.1/x.yaml",
                "https://localhost/x.yaml",
                "http://169.254.169.254/latest/meta-data/",
                "https://metadata.google.internal/x",
            ).forEach { url -> assertTrue("SSRF protection" in strict.rejects(url).message.orEmpty(), url) }
            assertEquals(emptyList(), requested)
        }

    @Test
    fun strictRejectsPrivateFileContentToo() =
        runTest {
            assertFailsWith<ConfigException> { fetcher(SsrfMode.STRICT).fetchFileContent("https://10.1.2.3/p.md", null) }
            assertEquals(emptyList(), requested)
        }

    @Test
    fun strictAllowLocalhostPermitsPrivateAndLoopback() =
        runTest {
            val strict = fetcher(SsrfMode.STRICT, allowLocalhost = true)
            strict.fetch(ConfigSource(url = "https://10.0.0.5/x.yaml"))
            strict.fetch(ConfigSource(url = "https://localhost:8443/x.yaml"))
            assertEquals(2, requested.size)
        }

    @Test
    fun strictAllowPrivateNetworkPermitsPrivateButNotLoopback() =
        runTest {
            withContext(NetworkPolicy(allowPrivateNetwork = true)) {
                val strict = fetcher(SsrfMode.STRICT)
                strict.fetch(ConfigSource(url = "https://10.0.0.5/x.yaml"))
                strict.fetch(ConfigSource(url = "https://intranet/x.yaml"))
                strict.rejects("https://localhost:8443/x.yaml")
                strict.rejects("https://169.254.169.254/x.yaml")
                assertEquals(2, requested.size)
                assertTrue(logger.warnings.none { "private-network" in it }, logger.warnings.toString())
            }
        }

    @Test
    fun redirectToPrivateIpIsNotFollowedEvenWithAnExplicitClient() =
        runTest {
            val fetcher = fetcher(SsrfMode.STRICT) {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://10.0.0.5/secret"))
            }
            val error = fetcher.rejects("https://example.com/base.yaml")
            assertTrue("HTTP 302" in error.message.orEmpty(), error.message)
            assertEquals(listOf("https://example.com/base.yaml"), requested)
        }

    @Test
    fun operatorFetchesPrivateHttpWithOneWarningPerUrl() =
        runTest {
            val operator = fetcher(SsrfMode.OPERATOR)
            repeat(2) { operator.fetch(ConfigSource(url = "http://10.0.0.5/base.yaml")) }
            assertEquals(2, requested.size)
            assertEquals(2, logger.warnings.size, logger.warnings.joinToString("\n"))
            assertTrue(logger.warnings.any { "private-network host" in it })
            assertTrue(logger.warnings.any { "plain HTTP" in it && "scripts:" in it && "http://10.0.0.5/base.yaml" in it })
        }

    @Test
    fun operatorRejectsMetadataAndLoopbackUnlessAllowLocalhost() =
        runTest {
            val operator = fetcher(SsrfMode.OPERATOR)
            operator.rejects("http://169.254.169.254/latest/meta-data/")
            val loopback = operator.rejects("http://127.0.0.1:4000/base.yaml")
            assertTrue("--allow-localhost" in loopback.message.orEmpty())
            assertEquals(emptyList(), requested)

            val allowed = fetcher(SsrfMode.OPERATOR, allowLocalhost = true)
            allowed.fetch(ConfigSource(url = "http://127.0.0.1:4000/base.yaml"))
            allowed.rejects("http://169.254.169.254/latest/meta-data/")
            assertEquals(listOf("http://127.0.0.1:4000/base.yaml"), requested)
        }

    @Test
    fun anonymousHttpWarnsOnFileContentPathAndAuthenticatedHttpsDoesNot() =
        runTest {
            val operator = fetcher(SsrfMode.OPERATOR)
            operator.fetchFileContent("http://example.com/prompt.md", null)
            operator.fetchFileContent("https://example.com/prompt.md", HttpAuth.Bearer("t0ken"))
            assertEquals(1, logger.warnings.size)
            assertTrue("plain HTTP" in logger.warnings.single())
        }

    @Test
    fun credentialsInUrlNeverReachWarningsOrRejections() =
        runTest {
            val operator = fetcher(SsrfMode.OPERATOR)
            operator.fetch(ConfigSource(url = "http://user:hunter2@10.0.0.5/x.yaml?private_token=abc123"))
            val rejected = operator.rejects("http://user:hunter2@169.254.169.254/?private_token=abc123")
            (logger.warnings + rejected.message.orEmpty()).forEach { text ->
                assertTrue("hunter2" !in text && "abc123" !in text, text)
            }
        }

    @Test
    fun declaredOversizedBodyIsRejected() =
        runTest {
            val fetcher = fetcher(SsrfMode.OPERATOR) {
                respond(
                    ByteReadChannel("x"),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentLength, (RemoteConfigFetcher.MAX_BODY_BYTES + 1).toString()),
                )
            }
            assertTrue("exceeds" in fetcher.rejects("https://example.com/big.yaml").message.orEmpty())
        }

    @Test
    fun undeclaredOversizedStreamIsRejectedWithoutReadingItAll() =
        runTest {
            val chunk = ByteArray(CHUNK_BYTES) { 'a'.code.toByte() }
            val stream = ByteChannel()
            val written = mutableListOf<Int>()
            backgroundScope.launch {
                runCatching { repeat(STREAM_CHUNKS) { stream.writeFully(chunk).also { written += 1 } } }
                stream.flushAndClose()
            }
            val fetcher = fetcher(SsrfMode.OPERATOR) { respond(stream, HttpStatusCode.OK) }
            assertTrue("exceeds" in fetcher.rejects("https://example.com/endless.yaml").message.orEmpty())
            assertTrue(written.size < STREAM_CHUNKS, "read ${written.size} chunks of an endless body")
        }

    @Test
    fun bodyAtTheCapIsAccepted() =
        runTest {
            val body = "a".repeat(RemoteConfigFetcher.MAX_BODY_BYTES.toInt())
            val fetcher = fetcher(SsrfMode.OPERATOR) { respond(body, HttpStatusCode.OK) }
            assertEquals(body.length, fetcher.fetch(ConfigSource(url = "https://example.com/max.yaml")).length)
        }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024

        /** 64 MiB in total - far over the cap; the fetch must stop long before the producer does. */
        const val STREAM_CHUNKS = 1024
    }
}
