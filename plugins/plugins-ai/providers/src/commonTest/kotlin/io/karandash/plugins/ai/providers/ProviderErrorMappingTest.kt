package io.karandash.plugins.ai.providers

import io.karandash.api.ai.AiProviderException
import io.karandash.duration.KarandashDuration
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Auth and missing-model errors are typed and never retried, SSRF guard rejections are not
 * retried, a truncated OpenAI reply is surfaced.
 */
class ProviderErrorMappingTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun openAi(
        engine: MockEngine,
        baseUrl: String = "https://api.openai.com/v1",
    ) = OpenAiProvider(apiKey = "k", baseUrl = baseUrl, timeout = KarandashDuration.ofSeconds(5), maxRetries = 2, httpClientEngine = engine)

    @Test
    fun `401 is AuthenticationFailed and 403 is not retried`() =
        runTest {
            val unauthorized = MockEngine { respond("""{"error":"bad key"}""", HttpStatusCode.Unauthorized, json) }
            val e = assertFailsWith<AiProviderException.AuthenticationFailed> { openAi(unauthorized).complete(prompt = "p", model = "m") }
            assertEquals("openai", e.provider)
            assertEquals(1, unauthorized.requestHistory.size)
            val forbidden = MockEngine { respond("""{"error":"no access"}""", HttpStatusCode.Forbidden, json) }
            assertFailsWith<AiProviderException> { openAi(forbidden).complete(prompt = "p", model = "m") }
            assertEquals(1, forbidden.requestHistory.size)
        }

    @Test
    fun `404 for a model is ModelNotAvailable and sent once`() =
        runTest {
            val engine = MockEngine {
                respond(
                    """{"error":{"message":"The model `gpt-x` does not exist","code":"model_not_found"}}""",
                    HttpStatusCode.NotFound,
                    json
                )
            }
            val e = assertFailsWith<AiProviderException.ModelNotAvailable> { openAi(engine).complete(prompt = "p", model = "gpt-x") }
            assertEquals("gpt-x", e.model)
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun `an SSRF guard rejection is not retried`() =
        runTest {
            val engine = MockEngine { respond("{}", HttpStatusCode.OK, json) }
            assertFailsWith<AiProviderException> { openAi(engine, "http://169.254.169.254/v1").complete(prompt = "p", model = "m") }
            assertEquals(0L, testScheduler.currentTime, "no backoff delay means no retry")
        }

    @Test
    fun `a connect timeout is not retried and later calls to the host fail at once`() =
        runTest {
            var sent = 0
            val engine = MockEngine {
                sent++
                throw ConnectTimeoutException(
                    "Connect timeout has expired [url=https://proxy.example/v1/chat/completions, connect_timeout=15000 ms]"
                )
            }
            val provider = openAi(engine, "https://proxy.example/v1")
            val logger = RecordingLogger()
            provider.initialize(RecordingContext(logger))
            val first = assertFailsWith<AiProviderException> { provider.complete(prompt = "p", model = "m") }
            assertEquals("unreachable: proxy.example (connect timeout)", first.message)
            assertEquals(false, first.retryable)
            val second = assertFailsWith<AiProviderException> { provider.complete(prompt = "p", model = "m") }
            assertEquals(first.message, second.message)
            assertEquals(1, sent, "one attempt in all: no retry, no second connect")
            assertEquals(
                1,
                logger.lines.count { it.startsWith("WARN") && "unreachable: proxy.example" in it },
                logger.lines.joinToString("\n")
            )
            assertTrue(logger.lines.none { it.startsWith("WARN") && "url=" in it }, "the raw message goes to DEBUG only")
        }

    @Test
    fun `a connect failure to a host that answered earlier this run is retried, not latched`() =
        runTest {
            val ok =
                """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,
                    |"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
                    |"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                """.trimMargin()
            var sent = 0
            val engine = MockEngine {
                sent++
                if (sent == 2) throw ConnectTimeoutException("Connect timeout has expired")
                respond(ok, HttpStatusCode.OK, json)
            }
            val provider = openAi(engine, "https://proxy.example/v1")
            provider.complete(prompt = "p", model = "m")
            assertEquals("hi", provider.complete(prompt = "p", model = "m").content)
            assertEquals(3, sent, "the failed connect was retried")
            provider.complete(prompt = "p", model = "m")
            assertEquals(4, sent, "later calls are not failed fast")
        }

    @Test
    fun `connection refused and dns failures are unreachable too`() =
        runTest {
            assertEquals(
                "connection refused",
                connectFailureKind(AiProviderException("x", cause = IllegalStateException("Connection refused")))
            )
            assertEquals("dns", connectFailureKind(IllegalStateException("Could not resolve host: proxy.example")))
            assertEquals(null, connectFailureKind(AiProviderException("Request timed out after 5m")))
        }

    @Test
    fun `the connect timeout is capped at 15s and a shorter ai timeout is kept`() =
        runTest {
            for ((timeout, expected) in listOf(KarandashDuration.ofMinutes(5) to 15_000L, KarandashDuration.ofSeconds(5) to 5_000L)) {
                var connect: Long? = null
                val engine = MockEngine {
                    connect = it.getCapabilityOrNull(HttpTimeoutCapability)?.connectTimeoutMillis
                    respond("{}", HttpStatusCode.InternalServerError, json)
                }
                val provider = OpenAiProvider(apiKey = "k", timeout = timeout, maxRetries = 0, httpClientEngine = engine)
                assertFailsWith<AiProviderException> { provider.complete(prompt = "p", model = "m") }
                assertEquals(expected, connect, "ai.timeout=$timeout")
            }
        }

    @Test
    fun `finish_reason length is logged as truncated`() =
        runTest {
            val body =
                """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,
                    |"message":{"role":"assistant","content":"{\"a\":"},"finish_reason":"length"}],
                    |"usage":{"prompt_tokens":1,"completion_tokens":5,"total_tokens":6}}
                """.trimMargin()
            val logger = RecordingLogger()
            val provider = openAi(MockEngine { respond(body, HttpStatusCode.OK, json) })
            provider.initialize(RecordingContext(logger))
            assertTrue(provider.complete(prompt = "p", model = "m").truncated)
            assertTrue(logger.lines.any { it.startsWith("WARN") && "truncated" in it }, logger.lines.joinToString("\n"))
        }

    @Test
    fun `finish_reason length with empty content is logged as hidden reasoning`() =
        runTest {
            val body =
                """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,
                    |"message":{"role":"assistant","content":null},"finish_reason":"length"}],
                    |"usage":{"prompt_tokens":1,"completion_tokens":1500,"total_tokens":1501}}
                """.trimMargin()
            val logger = RecordingLogger()
            val provider = openAi(MockEngine { respond(body, HttpStatusCode.OK, json) })
            provider.initialize(RecordingContext(logger))
            provider.complete(prompt = "p", model = "m")
            assertTrue(logger.lines.any { it.startsWith("WARN") && "hidden reasoning" in it }, logger.lines.joinToString("\n"))
        }
}
