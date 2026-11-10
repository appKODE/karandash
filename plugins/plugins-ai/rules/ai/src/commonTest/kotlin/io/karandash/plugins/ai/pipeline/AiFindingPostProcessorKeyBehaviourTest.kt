package io.karandash.plugins.ai.pipeline

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ai.constitution.model|principles` and `ai.grounding.verifier.enabled` as read from the `ai:` block. */
class AiFindingPostProcessorKeyBehaviourTest {
    private val processor = AiFindingPostProcessor(NoOpLogger)

    private class RecordingService(
        private val content: String,
    ) : AiService {
        val requests = mutableListOf<AiCompletionRequest>()

        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            requests += request
            return AiCompletionResponse(content, 1, 1, 2, request.model)
        }
    }

    private val finding =
        Finding(severity = Severity.WARNING, message = "style nit", rule = "ai-review", location = Location.FileRef("A.kt", 1))
    private val mr = createTestMergeRequest(
        changes = listOf(FileChange(path = "A.kt", changeType = ChangeType.MODIFIED, diffContent = "+val a = 1")),
    )

    private fun config(json: String) = Json.parseToJsonElement("""{"ai": $json}""")

    @Test
    fun `constitution model is the model of the review call`() =
        runTest {
            val service = RecordingService("""{"findings": []}""")
            val cfg = config("""{"constitution": {"model": "judge-model", "principles": ["no style"]}}""")

            processor.applyConstitutionalReview(listOf(finding), cfg, service)

            assertEquals(listOf("judge-model"), service.requests.map { it.model })
        }

    @Test
    fun `constitution principles are sent in the review prompt`() =
        runTest {
            val service = RecordingService("""{"findings": []}""")
            val cfg = config("""{"constitution": {"principles": ["never nag about naming"]}}""")

            processor.applyConstitutionalReview(listOf(finding), cfg, service)

            assertTrue(
                service.requests
                    .single()
                    .prompt
                    .contains("never nag about naming")
            )
        }

    @Test
    fun `grounding verifier enabled asks the model and drops an invalid finding`() =
        runTest {
            val reply = """{"verdicts":[{"index":0,"verdict":"invalid","reason":"r"}]}"""
            val off = RecordingService(reply)
            val on = RecordingService(reply)

            val kept = processor.applyVerifier(listOf(finding), mr, config("""{"grounding": {"verifier": {}}}"""), off)
            val dropped = processor.applyVerifier(
                listOf(finding),
                mr,
                config("""{"grounding": {"verifier": {"enabled": true}}}"""),
                on,
            )

            assertEquals(listOf(finding), kept)
            assertEquals(0, off.requests.size)
            assertEquals(1, on.requests.size)
            assertTrue(dropped.isEmpty())
        }
}
