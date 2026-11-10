package io.karandash.plugins.ai.providers

import io.karandash.api.ai.AiProviderException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FallbackAiProviderTest {
    private class FakeProvider(
        private val failModels: Set<String> = emptySet(),
        private val nonRetryableModels: Set<String> = emptySet(),
        private val errors: Map<String, AiProviderException> = emptyMap(),
    ) : AiProvider {
        override val providerId: String = "fake"
        val calledModels = mutableListOf<String>()

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse {
            calledModels.add(model)
            errors[model]?.let { throw it }
            if (model in nonRetryableModels) {
                throw AiProviderException("Non-retryable for $model", statusCode = 401, retryable = false)
            }
            if (model in failModels) {
                throw AiProviderException("Failed for $model", statusCode = 500, retryable = true)
            }
            return AiResponse(
                content = "response from $model",
                promptTokens = 10,
                completionTokens = 20,
                totalTokens = 30,
                model = model,
                durationMs = 100,
            )
        }

        override suspend fun close() {}
    }

    @Test
    fun `first model succeeds without fallback`() =
        runTest {
            val delegate = FakeProvider()
            val provider = FallbackAiProvider(delegate, listOf("model-b")) { _, _ -> }

            val response = provider.complete("prompt", model = "model-a")

            assertEquals("response from model-a", response.content)
            assertEquals(listOf("model-a"), delegate.calledModels)
        }

    @Test
    fun `a model the provider does not have falls through to the next model`() =
        runTest {
            val delegate = FakeProvider(errors = mapOf("model-a" to AiProviderException.ModelNotAvailable("model-a")))
            val provider = FallbackAiProvider(delegate, listOf("model-b")) { _, _ -> }

            assertEquals("response from model-b", provider.complete("prompt", model = "model-a").content)
            assertEquals(listOf("model-a", "model-b"), delegate.calledModels)
        }

    @Test
    fun `a failed authentication stops the chain after one call`() =
        runTest {
            val delegate = FakeProvider(errors = mapOf("model-a" to AiProviderException.AuthenticationFailed("fake")))
            val provider = FallbackAiProvider(delegate, listOf("model-b")) { _, _ -> }

            assertFailsWith<AiProviderException.AuthenticationFailed> { provider.complete("prompt", model = "model-a") }
            assertEquals(listOf("model-a"), delegate.calledModels)
        }

    @Test
    fun `falls back to second model on retryable error`() =
        runTest {
            val delegate = FakeProvider(failModels = setOf("model-a"))
            val fallbackLog = mutableListOf<String>()
            val provider = FallbackAiProvider(delegate, listOf("model-b")) { m, _ ->
                fallbackLog.add(m)
            }

            val response = provider.complete("prompt", model = "model-a")

            assertEquals("response from model-b", response.content)
            assertEquals(listOf("model-a", "model-b"), delegate.calledModels)
            assertEquals(listOf("model-a"), fallbackLog)
        }

    @Test
    fun `does not fallback on non-retryable error`() =
        runTest {
            val delegate = FakeProvider(nonRetryableModels = setOf("model-a"))
            val provider = FallbackAiProvider(delegate, listOf("model-b")) { _, _ -> }

            val ex = assertFailsWith<AiProviderException> {
                provider.complete("prompt", model = "model-a")
            }

            assertEquals(401, ex.statusCode)
            assertEquals(listOf("model-a"), delegate.calledModels)
        }

    @Test
    fun `all models fail throws last exception`() =
        runTest {
            val delegate = FakeProvider(failModels = setOf("model-a", "model-b", "model-c"))
            val provider = FallbackAiProvider(delegate, listOf("model-b", "model-c")) { _, _ -> }

            val ex = assertFailsWith<AiProviderException> {
                provider.complete("prompt", model = "model-a")
            }

            assertTrue(ex.message.orEmpty().contains("model-c"))
            assertEquals(listOf("model-a", "model-b", "model-c"), delegate.calledModels)
        }

    @Test
    fun `empty chain uses caller-supplied model`() =
        runTest {
            val delegate = FakeProvider()
            val provider = FallbackAiProvider(delegate, emptyList()) { _, _ -> }

            val response = provider.complete("prompt", model = "caller-model")

            assertEquals("response from caller-model", response.content)
            assertEquals(listOf("caller-model"), delegate.calledModels)
        }

    @Test
    fun `primary model is tried before the fallback chain and not repeated`() =
        runTest {
            val delegate = FakeProvider(failModels = setOf("primary"))
            val provider = FallbackAiProvider(delegate, listOf("fb-1", "primary", "fb-2")) { _, _ -> }

            val response = provider.complete("prompt", model = "primary")

            assertEquals("response from fb-1", response.content)
            assertEquals(listOf("primary", "fb-1"), delegate.calledModels)
        }

    @Test
    fun `fallback is logged at info`() =
        runTest {
            val log = RecordingLogger()
            val provider = FallbackAiProvider(FakeProvider(failModels = setOf("primary")), listOf("fb"), log) { _, _ -> }

            provider.complete("prompt", model = "primary")

            assertTrue(log.lines.any { it.startsWith("INFO [ai] fallback: model primary failed") }, "${log.lines}")
        }

    @Test
    fun `providerId delegates to underlying provider`() {
        val delegate = FakeProvider()
        val provider = FallbackAiProvider(delegate, emptyList()) { _, _ -> }

        assertEquals("fake", provider.providerId)
    }
}
