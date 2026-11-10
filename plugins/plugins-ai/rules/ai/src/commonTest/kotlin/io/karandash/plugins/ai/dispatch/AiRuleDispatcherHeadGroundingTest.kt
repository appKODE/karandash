package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Evidence grounding checks a chunk's findings against the whole MR at head, not the chunk alone. */
class AiRuleDispatcherHeadGroundingTest {
    @Test
    fun `a finding on an added line of a file changed in another chunk survives`() =
        runTest {
            assertEquals(listOf("B.kt:1"), kept(listOf(at("B.kt", 1))))
        }

    @Test
    fun `findings on a deleted path an absent path or an unchanged line are dropped`() =
        runTest {
            assertEquals(emptyList(), kept(listOf(at("C.kt", 1), at("D.kt", 1), at("B.kt", 5))))
        }

    @Test
    fun `a finding on a binary or purely renamed file of another chunk is dropped`() =
        runTest {
            assertEquals(listOf("B.kt:1"), kept(listOf(at("E.png", 1), at("R.kt", 1), at("B.kt", 1))))
        }

    @Test
    fun `a finding on a file the rule scope left out is dropped and one on a reviewed file survives`() =
        runTest {
            assertEquals(listOf("A.kt:1"), kept(listOf(at("A.kt", 1), at("B.kt", 1)), chunked = false))
        }

    private suspend fun kept(
        findings: List<Finding>,
        chunked: Boolean = true,
    ): List<String> {
        val analyzer = FixedAnalyzer(RULE, findings)
        val registry = object : ModuleRegistry by buildTestRegistry() {
            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { analyzerId == RULE }
        }
        val a = change("A.kt", ChangeType.MODIFIED, DIFF)
        val b = change("B.kt", ChangeType.MODIFIED, DIFF)
        val c = change("C.kt", ChangeType.DELETED, "@@ -1,1 +0,0 @@\n-gone")
        val binary = change("E.png", ChangeType.MODIFIED, "Binary files a/E.png and b/E.png differ")
        val renamed = FileChange(path = "R.kt", changeType = ChangeType.RENAMED)
        val head: MergeRequest = createTestMergeRequest(changes = listOf(a, b, c, binary, renamed))
        val result = AiRuleDispatcher(registry, NoOpLogger).dispatch(
            AiRuleDispatcher.DispatchInput(
                ruleId = RULE,
                mr = head.copy(changes = listOf(a, c)),
                concurrencySemaphore = Semaphore(1),
                aiProvider = UnusedProvider,
                budget = TokenBudget(maxTokens = 100_000),
                model = "m",
                config = JsonObject(emptyMap()),
                resolvedPrompt = null,
                aiService = UnusedService,
                aiRuleConfig = AiRuleConfig(),
                pluginCfg = AiPluginConfig(reasoning = "semi-formal"),
                contextProcessors = emptyList(),
                accumulatedFindings = emptyList(),
                customRules = emptyList(),
                fullMr = head,
                // chunked: B.kt and the rest sit in another chunk of the same rule; otherwise only A.kt and C.kt are in scope
                rulePaths = if (chunked) head.changes.mapTo(mutableSetOf()) { it.path } else null,
            ),
        )
        return result.findings.map { f -> (f.location as Location.FileRef).let { "${it.path}:${it.line}" } }
    }

    private fun change(
        path: String,
        type: ChangeType,
        diff: String,
    ) = FileChange(path = path, changeType = type, additions = 1, deletions = 1, diffContent = diff)

    private fun at(
        path: String,
        line: Int,
    ) = Finding(Severity.WARNING, "issue", RULE, Location.FileRef(path, line))

    private class FixedAnalyzer(
        override val analyzerId: String,
        private val findings: List<Finding>,
    ) : AiAnalyzerModule {
        override val id: String = analyzerId
        override val name: String = analyzerId
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = analyzerId
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = 0
        override val supportsSemiFormalReasoning: Boolean = true

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> = findings
    }

    private object UnusedService : AiService {
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse = error("the fixed analyzer never calls the service")
    }

    private object UnusedProvider : AiProvider {
        override val providerId = "unused"

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse = error("analyzers call through AiService")

        override suspend fun close() = Unit
    }

    private companion object {
        const val RULE = "review-comments"
        const val DIFF = "@@ -1,1 +1,1 @@\n-old line\n+new line"
    }
}
