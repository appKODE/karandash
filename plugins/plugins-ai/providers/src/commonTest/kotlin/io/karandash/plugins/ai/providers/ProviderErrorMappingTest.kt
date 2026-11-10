package io.karandash.plugins.ai.providers

import io.karandash.api.ai.AiProviderException
import io.karandash.duration.KarandashDuration
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
