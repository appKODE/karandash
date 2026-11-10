package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NoJsonRecoveryTest {
    private val truncated = """{"findings":[{"message":"a"},{"message":"b"""
    private val complete = """{"findings":[{"message":"a"}]}"""

    private class FixedService(
        private val content: String,
    ) : AiService {
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ) = AiCompletionResponse(content, 1, 1, 2, request.model)
    }

    @Test
    fun `truncated json is discarded`() {
        assertEquals("", NoJsonRecovery.discardTruncated(truncated, "ai-x", NoOpLogger))
    }

    @Test
    fun `complete json and prose pass through`() {
        assertEquals(complete, NoJsonRecovery.discardTruncated(complete, "ai-x", NoOpLogger))
        assertEquals("no issues", NoJsonRecovery.discardTruncated("no issues", "ai-x", NoOpLogger))
    }

    @Test
    fun `service wrapper empties truncated responses and keeps token counts`() =
        runTest {
            val service = NoJsonRecovery.Service(FixedService(truncated), "ai-x", NoOpLogger)
            val response = service.complete(AiCompletionRequest(prompt = "p", model = "m"))
            assertEquals("", response.content)
            assertEquals(2, response.totalTokens)
        }
}
