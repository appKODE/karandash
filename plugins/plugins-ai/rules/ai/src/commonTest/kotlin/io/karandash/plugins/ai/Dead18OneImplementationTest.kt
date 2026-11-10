package io.karandash.plugins.ai

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiService
import io.karandash.cache.CacheTier
import io.karandash.cache.TieredCache
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.MrCacheKey
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.SnapshotManager
import io.karandash.plugins.ai.core.config.RoutingConfig
import io.karandash.plugins.ai.core.pipeline.AiGroundingVerifier
import io.karandash.plugins.ai.core.pipeline.FindingVerifier
import io.karandash.plugins.ai.core.router.ModelRouter
import io.karandash.plugins.ai.core.router.ModelTier
import io.karandash.plugins.ai.pipeline.AiFindingPostProcessor
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.plugins.ai.providers.CachingAiProvider
import io.karandash.plugins.ai.providers.withResponseChain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import io.karandash.plugins.ai.core.incremental.SnapshotManager as IncrementalSnapshotManager

/**
 * each AI-core duplicate is one implementation, the old names are typealiases of it.
 * none of them is dead; each test drives the one implementation through the path a run takes.
 */
class Dead18OneImplementationTest {
    private val mr =
        MergeRequest(
            id = "1",
            iid = 7,
            title = "t",
            description = null,
            sourceBranch = "f",
            targetBranch = "main",
            author = User(username = "dev", name = "Developer"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = listOf(FileChange(path = "A.kt", changeType = ChangeType.MODIFIED, diffContent = "+val total = price * qty")),
        )

    private val finding =
        Finding(
            severity = Severity.WARNING,
            message = "bogus",
            rule = "review-comments",
            location = Location.FileRef("A.kt", 1),
            metadata = mapOf(AiFindingMetadataKeys.AI_PROVIDER to "openai"),
        )

    private class InvalidVerdicts : AiService {
        val calls = mutableListOf<String>()
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            calls += request.model
            return AiCompletionResponse("""{"verdicts":[{"index":0,"verdict":"invalid","reason":"r"}]}""", 1, 1, 2, request.model)
        }
    }

    /** Fails every model but [answers] with a retryable error, so the fallback chain moves on. */
    private class OnlyModel(
        private val answers: String,
    ) : AiProvider {
        val tried = mutableListOf<String>()
        override val providerId = "fake"

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse {
            tried += model
            if (model != answers) throw AiProviderException("down", retryable = true)
            return AiResponse("ok", 1, 1, 2, model)
        }

        override suspend fun close() = Unit
    }

    private fun aiConfig(json: String): AiPluginConfig = AiPluginConfig.fromPluginConfig(Json.parseToJsonElement(json).jsonObject)

    private fun cache() = TieredCache<String, AiResponse>(name = "responses", maxEntries = 10, tier = CacheTier.MEMORY_ONLY)

    @Test
    fun `the verifier pass is the grounding verifier and the post processor drops the invalid finding`() =
        runTest {
            val service = InvalidVerdicts()
            val direct = FindingVerifier(service, "cheap")
            assertIs<AiGroundingVerifier>(direct)
            assertTrue(direct.secondOpinion(listOf(finding), mr).isEmpty())

            val cfg = Json.parseToJsonElement("""{"ai": {"model": "m", "grounding": {"verifier": {"enabled": true}}}}""")
            assertTrue(AiFindingPostProcessor(NoOpLogger).applyVerifier(listOf(finding), mr, cfg, service).isEmpty())
            assertEquals(listOf("cheap", "m"), service.calls)
        }

    @Test
    fun `grounding still refutes an unfounded symbol on the post processor path`() =
        runTest {
            val unfounded = finding.copy(message = "`missingSymbol` is never closed")
            val cfg = Json.parseToJsonElement("""{"ai": {"grounding": {"enabled": true, "failureMode": "downgrade"}}}""")

            val result = AiFindingPostProcessor(NoOpLogger).applyGrounding(listOf(unfounded), mr, cfg)

            assertEquals(Severity.INFO, result.single().severity)
            assertTrue(result.single().message.startsWith("[REFUTED"))
        }

    @Test
    fun `the risk router is the model router`() {
        val tiers = listOf(ModelTier("strong", listOf("big")), ModelTier("cheap", listOf("small")))
        val routing = RoutingConfig(tiers = tiers)

        assertEquals("small", ModelRouter.route(routing, "review-comments", riskScore = 0.1))
        assertEquals("big", ModelRouter.route(routing, "review-comments", riskScore = 0.9))
    }

    @Test
    fun `both snapshot manager names key an MR the same way`() {
        assertEquals(MrCacheKey.buildKey(mr), SnapshotManager.mrKey(mr))
        assertEquals(SnapshotManager.mrKey(mr), IncrementalSnapshotManager.mrKey(mr))
    }

    @Test
    fun `one provider chain applies model fallback then cache`() =
        runTest {
            val base = OnlyModel(answers = "b")
            val fallbackOnly = base.withResponseChain(aiConfig("""{"modelFallback": ["b"]}"""), cache(), NoOpLogger)
            assertIs<CachingAiProvider>(fallbackOnly)
            assertEquals("b", fallbackOnly.complete("p", model = "a").model)
            assertEquals(listOf("a", "b"), base.tried)

            // consensus is a per-rule finding vote (ConsensusRun), never a provider wrapper
            val consensusCfg = aiConfig("""{"consensusModels": ["b", "b"]}""")
            assertIs<CachingAiProvider>(base.withResponseChain(consensusCfg, cache(), NoOpLogger))
        }
}
