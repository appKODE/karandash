package io.karandash.plugins.ai.pipeline

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.logger.NoOpLogger
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.TokenBudget
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ai.grounding.verifier.enabled|model` as read from the `ai:` block. */
class AiFindingVerifierKeysTest {
    private val processor = AiFindingPostProcessor(NoOpLogger)

    private class ScriptedService : AiService {
        val models = mutableListOf<String>()
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            models += request.model
            return AiCompletionResponse("""{"verdicts":[{"index":0,"verdict":"invalid","reason":"r"}]}""", 1, 1, 2, request.model)
        }
    }

    private val finding =
        Finding(
            severity = Severity.WARNING,
            message = "bogus",
            rule = "review-comments",
            location = Location.FileRef("A.kt", 1),
            metadata = mapOf(AiFindingMetadataKeys.AI_PROVIDER to "openai"),
        )
    private val mr =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "t",
            description = null,
            sourceBranch = "f",
            targetBranch = "main",
            author = User(username = "dev", name = "Developer"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
        )

    private fun config(json: String) = Json.parseToJsonElement("""{"ai": $json}""")

    @Test
    fun `the verifier is off by default and makes no call`() =
        runTest {
            val service = ScriptedService()

            assertEquals(listOf(finding), processor.applyVerifier(listOf(finding), mr, config("{}"), service))
            assertTrue(service.models.isEmpty())
        }

    @Test
    fun `the verifier defaults to the main model`() =
        runTest {
            val service = ScriptedService()
            val cfg = config("""{"model": "main-model", "grounding": {"verifier": {"enabled": true}}}""")

            assertTrue(processor.applyVerifier(listOf(finding), mr, cfg, service).isEmpty())
            assertEquals(listOf("main-model"), service.models)
        }

    @Test
    fun `the verifier model key picks the cheaper model`() =
        runTest {
            val service = ScriptedService()
            val cfg = config("""{"model": "main-model", "grounding": {"verifier": {"enabled": true, "model": "small"}}}""")

            processor.applyVerifier(listOf(finding), mr, cfg, service)

            assertEquals(listOf("small"), service.models)
        }

    @Test
    fun `the verifier call is recorded in the run token budget`() =
        runTest {
            val budget = TokenBudget(100_000)
            val cfg = config("""{"grounding": {"verifier": {"enabled": true}}}""")

            processor.applyVerifier(listOf(finding), mr, cfg, ScriptedService(), budget = budget)

            assertEquals(2, budget.totalConsumed())
        }
}
