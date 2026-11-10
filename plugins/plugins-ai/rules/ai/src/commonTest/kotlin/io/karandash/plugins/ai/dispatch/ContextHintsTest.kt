@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.run.RunDump
import io.karandash.api.run.RunServiceKeys
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.pipeline.HintsConfig
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `ai.hints`: moved declarations and where the identifiers a call uses are declared. */
class ContextHintsTest {
    private val moved = change(
        "Old.kt",
        "@@ -1,3 +1,1 @@\n-fun parseToken(raw: String) = raw\n-class Session\n val keep = 1",
        "val keep = 1",
    )
    private val target =
        change(
            "New.kt",
            "@@ -0,0 +1,2 @@\n+fun parseToken(raw: String) = raw\n+class Session",
            "fun parseToken(raw: String) = raw\nclass Session"
        )
    private val user = change("Use.kt", "@@ -1 +1 @@\n-val s = 1\n+val s = Session()", "val s = Session()")
    private val all = listOf(moved, target, user)

    @Test
    fun `a removed declaration still declared in another MR file is a moved hint`() {
        assertEquals(
            listOf(
                "- `Session` removed in Old.kt is still declared in New.kt",
                "- `parseToken` removed in Old.kt is still declared in New.kt",
            ),
            ContextHints.lines(listOf(moved), all),
        )
    }

    @Test
    fun `an identifier the diff uses that another MR file declares is a defined hint`() {
        assertEquals(listOf("- `Session`, used in this diff, is declared in New.kt"), ContextHints.lines(listOf(user), all))
    }

    @Test
    fun `the hints are a context block of the review-comments call and dumped as hints json`() =
        runTest {
            val dumps = mutableListOf<Pair<String, String>>()
            val session = ModuleRegistryImpl().forRun()
            session.provide(RunServiceKeys.DUMP, RunDump { name, content -> dumps += name to content })

            val call = input(listOf(user))
            val hinted = withContext(session.services) { ContextHints.forCall(call, all) }

            val block = hinted.contextBlocks.single()
            assertEquals(ContextHints.SOURCE, block.source)
            assertTrue("`Session`, used in this diff, is declared in New.kt" in block.content, block.content)
            assertTrue(hinted.plannedContextTokens > 0)
            val dump = dumps.single { it.first == "ai/stages/hints.json" }.second
            assertTrue("\"rule\": \"review-comments\"" in dump, dump)
            assertTrue("Use.kt" in dump, dump)
        }

    @Test
    fun `disabled hints other rules and no facts leave the call as is`() =
        runTest {
            val off = input(listOf(user), HintsConfig(enabled = false))
            assertSame(off, ContextHints.forCall(off, all))
            val other = input(listOf(user), rule = "risk-scoring")
            assertSame(other, ContextHints.forCall(other, all))
            val none = input(listOf(change("Z.kt", "@@ -1 +1 @@\n-a\n+b", "b")))
            assertSame(none, ContextHints.forCall(none, all))
        }

    @Test
    fun `maxTokens keeps only the hint lines that fit`() =
        runTest {
            val many = (1..40).map { change("D$it.kt", "@@ -0,0 +1 @@\n+fun helperNumber$it() = $it", "fun helperNumber$it() = $it") }
            val caller = change("Use.kt", "@@ -1 +1 @@\n+" + (1..40).joinToString(" ") { "helperNumber$it()" }, "")
            val wide = ContextHints
                .forCall(
                    input(listOf(caller), HintsConfig(maxTokens = 10_000)),
                    many + caller
                ).contextBlocks
                .single()
                .content
            val narrow = ContextHints.forCall(input(listOf(caller), HintsConfig(maxTokens = 60)), many + caller)

            assertEquals(40, wide.lines().size)
            val lines = narrow.contextBlocks
                .single()
                .content
                .lines()
            assertTrue(lines.size in 1 until 40, "${lines.size}")
            assertTrue(narrow.plannedContextTokens <= 60)
        }

    private fun input(
        changes: List<FileChange>,
        hints: HintsConfig = HintsConfig(),
        rule: String = AiRuleIds.REVIEW_COMMENTS,
    ) = AiRuleDispatcher.DispatchInput(
        ruleId = rule,
        mr = createTestMergeRequest(changes = changes),
        concurrencySemaphore = Semaphore(1),
        aiProvider = UnusedProvider,
        budget = TokenBudget(maxTokens = 100_000),
        model = "m",
        config = JsonObject(emptyMap()),
        resolvedPrompt = null,
        aiService = null,
        aiRuleConfig = AiRuleConfig(),
        pluginCfg = AiPluginConfig(hints = hints),
        contextProcessors = emptyList(),
        accumulatedFindings = emptyList(),
        customRules = emptyList(),
    )

    private fun change(
        path: String,
        diff: String,
        content: String,
    ) = FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 1, diffContent = diff, newContent = content)

    private object UnusedProvider : AiProvider {
        override val providerId = "unused"

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse = error("not called")

        override suspend fun close() = Unit
    }
}
