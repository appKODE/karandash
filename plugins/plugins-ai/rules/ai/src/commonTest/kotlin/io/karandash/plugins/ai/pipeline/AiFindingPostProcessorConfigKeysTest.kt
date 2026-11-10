package io.karandash.plugins.ai.pipeline

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ai.constitution.enabled|failureMode` and `ai.grounding.failureMode` as read from the `ai:` block. */
class AiFindingPostProcessorConfigKeysTest {
    private val processor = AiFindingPostProcessor(NoOpLogger)

    private class ScriptedService(
        private val content: String,
    ) : AiService {
        var calls = 0

        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            calls++
            return AiCompletionResponse(content, 1, 1, 2, request.model)
        }
    }

    private val removeAll = """{"findings": [{"index": 0, "verdict": "remove"}]}"""
    private val finding =
        Finding(severity = Severity.WARNING, message = "style nit", rule = "ai-review", location = Location.FileRef("A.kt", 1))

    private fun config(json: String) = Json.parseToJsonElement("""{"ai": $json}""")

    @Test
    fun `constitution is off by default and makes no AI call without principles`() =
        runTest {
            val service = ScriptedService(removeAll)

            for (cfg in listOf(config("{}"), config("""{"constitution": {"enabled": true}}"""))) {
                assertEquals(listOf(finding), processor.applyConstitutionalReview(listOf(finding), cfg, service))
            }
            assertEquals(0, service.calls, "A12: constitutional review runs only when principles are configured")
        }

    @Test
    fun `constitution enabled false skips the pass despite principles`() =
        runTest {
            val service = ScriptedService(removeAll)
            val cfg = config("""{"constitution": {"enabled": false, "principles": ["no style"]}}""")

            val result = processor.applyConstitutionalReview(listOf(finding), cfg, service)

            assertEquals(listOf(finding), result)
            assertEquals(0, service.calls)
        }

    @Test
    fun `constitution failureMode downgrade keeps the finding as info`() =
        runTest {
            val cfg = config("""{"constitution": {"failureMode": "downgrade", "principles": ["no style"]}}""")

            val result = processor.applyConstitutionalReview(listOf(finding), cfg, ScriptedService(removeAll))

            assertEquals(Severity.INFO, result.single().severity)
            assertTrue(result.single().message.startsWith("[CONSTITUTION]"))
        }

    @Test
    fun `constitution default removes the finding`() =
        runTest {
            val cfg = config("""{"constitution": {"principles": ["no style"]}}""")

            assertTrue(processor.applyConstitutionalReview(listOf(finding), cfg, ScriptedService(removeAll)).isEmpty())
        }

    @Test
    fun `grounding failureMode remove drops refuted finding`() =
        runTest {
            val refuted = finding.copy(message = "Missing null check for `phantomMethod`")
            val mr = MergeRequest(
                id = "1",
                iid = 1,
                title = "t",
                description = null,
                sourceBranch = "f",
                targetBranch = "main",
                author = User("1", "dev", "Dev"),
                state = MergeRequestState.OPENED,
                draft = false,
                labels = emptyList(),
                commits = emptyList(),
                changes = listOf(FileChange(path = "A.kt", changeType = ChangeType.MODIFIED, diffContent = "+fun realMethod() = 1")),
            )

            val downgraded = processor.applyGrounding(listOf(refuted), mr, config("""{"grounding": {"failureMode": "downgrade"}}"""))
            val removed = processor.applyGrounding(listOf(refuted), mr, config("""{"grounding": {"failureMode": "remove"}}"""))

            assertEquals(Severity.INFO, downgraded.single().severity)
            assertTrue(removed.isEmpty())
        }
}
