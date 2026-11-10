@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.run.RepositoryFileReader
import io.karandash.api.run.RunServiceKeys
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.ReviewStrictness
import io.karandash.plugins.ai.core.config.CrossFileContextConfig
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.prompts.PromptResolver
import io.karandash.plugins.ai.prompts.PromptSource
import io.karandash.sdk.content.ContentSource
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Top-level `ai` prompt keys that end up as named parts of the assembled prompt. */
class AiPromptAssemblerKeyBehaviourTest {
    private val mr = createTestMergeRequest(
        changes = listOf(FileChange(path = "src/A.kt", changeType = ChangeType.MODIFIED, diffContent = "+fun f1() = Unit")),
    )

    private suspend fun assemble(
        cfg: AiPluginConfig,
        request: MergeRequest = mr,
    ): AssembledExtras =
        AiPromptAssembler(buildTestRegistry(), NoOpLogger).assembleExtras(
            ruleId = "review-comments",
            mr = request,
            changes = request.changes,
            pluginCfg = cfg,
            aiRuleConfig = AiRuleConfig(),
            strictness = ReviewStrictness.STANDARD,
            feedbackStore = FeedbackStore(),
            needsScopeDetection = { false },
        )

    @Test
    fun `ai extraInstructions is sent as a prompt part`() =
        runTest {
            val extras = assemble(AiPluginConfig(extraInstructions = ContentSource.Inline("Prefer small diffs")))
            assertEquals("Prefer small diffs", extras.parts.single { it.name == "extraInstructions" }.text)
        }

    @Test
    fun `ai bestPractices is sent under a best practices heading`() =
        runTest {
            val extras = assemble(AiPluginConfig(bestPractices = ContentSource.Inline("Name things well")))
            assertEquals("Best practices:\nName things well", extras.parts.single { it.name == "bestPractices" }.text)
        }

    @Test
    fun `ai crossFileContext enabled false drops the cross-file context part`() =
        runTest {
            assertTrue(assemble(AiPluginConfig()).parts.any { it.name == "crossFileContext" })
            val off = assemble(AiPluginConfig(crossFileContext = CrossFileContextConfig(enabled = false)))
            assertTrue(off.parts.none { it.name == "crossFileContext" })
        }

    @Test
    fun `ai context values fill the variables of a configured prompt`() =
        runTest {
            val cfg = AiPluginConfig(context = mapOf("team_name" to "platform"))
            val resolved = AiPromptAssembler(buildTestRegistry(), NoOpLogger).resolvePromptForRule(
                ruleId = "review-comments",
                mr = mr,
                combinedExtra = null,
                promptSources = mapOf("review-comments" to PromptSource.Inline("Team: {{team_name}}")),
                promptResolver = PromptResolver(),
                pluginCfgContext = cfg.context,
            )
            assertTrue(checkNotNull(resolved).systemPrompt.contains("Team: platform"))
        }

    @Test
    fun `ai crossFileContextMaxChars cuts the cross-file context part`() =
        runTest {
            val full = assemble(AiPluginConfig()).parts.single { it.name == "crossFileContext" }.text
            val cut = assemble(AiPluginConfig(crossFileContextMaxChars = 5)).parts.single { it.name == "crossFileContext" }.text
            assertTrue(cut.length < full.length, "$cut vs $full")
        }

    @Test
    fun `ai crossFileContext reads an included header through the platform when it is not checked out`() =
        runTest {
            val cpp = FileChange(
                path = "search/ranker.cpp",
                changeType = ChangeType.MODIFIED,
                diffContent = "+  return b;",
                newContent = "#include \"search/token_slice.hpp\"\nint f() { return b; }\n",
            )
            val session = ModuleRegistryImpl().forRun()
            val reads = mutableListOf<String>()
            session.provide(
                RunServiceKeys.REPOSITORY_FILE,
                RepositoryFileReader { ref, path ->
                    reads += "$ref:$path"
                    "class TokenSliceNoCategories {};".takeIf { path == "search/token_slice.hpp" }
                },
            )
            val request = createTestMergeRequest(changes = listOf(cpp))
            val extras = withContext(session.services) {
                assemble(AiPluginConfig(crossFileContext = CrossFileContextConfig(enabled = true)), request)
            }.also { session.close() }

            val block = extras.parts.single { it.name == AssembledExtras.DEPENDENCY_CONTEXT }.text
            assertTrue("TokenSliceNoCategories" in block, block)
            assertTrue(reads.all { it.startsWith("${request.diffRefs?.headSha ?: request.sourceBranch}:") }, reads.toString())
        }
}
