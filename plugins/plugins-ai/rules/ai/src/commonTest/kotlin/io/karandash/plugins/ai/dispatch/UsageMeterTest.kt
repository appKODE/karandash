package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class UsageMeterTest {
    private val service =
        object : AiService {
            override val isAvailable = true

            override suspend fun complete(
                request: AiCompletionRequest,
                providerId: String?,
            ): AiCompletionResponse = AiCompletionResponse("{}", 10, 15, 25, "model-x")
        }

    private val finding = Finding(severity = Severity.INFO, rule = "r", message = "m")

    @Test
    fun `no completion leaves findings untouched`() {
        assertSame(finding, UsageMeter().stamp(finding))
    }

    @Test
    fun `completions stamp the model and the summed tokens`() =
        runTest {
            val meter = UsageMeter()
            val watched = meter.watch(service)
            watched.complete(AiCompletionRequest(prompt = "p", model = "m"), null)
            watched.complete(AiCompletionRequest(prompt = "p", model = "m"), null)

            val metadata = meter.stamp(finding).metadata
            assertEquals("model-x", metadata[AiFindingMetadataKeys.AI_MODEL])
            assertEquals("50", metadata[AiFindingMetadataKeys.AI_TOKENS_USED])
        }

    @Test
    fun `values the analyzer set win`() =
        runTest {
            val meter = UsageMeter()
            meter.watch(service).complete(AiCompletionRequest(prompt = "p", model = "m"), null)
            val own = finding.copy(metadata = mapOf(AiFindingMetadataKeys.AI_TOKENS_USED to "0"))

            val metadata = meter.stamp(own).metadata
            assertEquals("0", metadata[AiFindingMetadataKeys.AI_TOKENS_USED])
            assertEquals("model-x", metadata[AiFindingMetadataKeys.AI_MODEL])
        }
}
