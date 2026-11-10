package io.karandash.plugins.ai.dispatch

import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.DiffContextBuilder
import io.karandash.plugins.ai.core.DiffMode
import io.karandash.plugins.ai.core.DiffPreprocessorOptions
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SizingDumpTest {
    @Test
    fun `the budget plan dump names every rule sizing and its sources`() {
        val shares = linkedMapOf(
            AiRuleIds.REVIEW_COMMENTS to TokenBudget(maxTokens = SHARE),
            AiRuleIds.CHECKS to TokenBudget(maxTokens = SHARE),
        )
        val input = ChunkBudget.Input(
            shares = shares,
            run = TokenBudget(maxTokens = 2 * SHARE),
            mr = createTestMergeRequest(changes = (1..4).map { change("F$it.kt", "+" + "y".repeat(10_000)) }),
            pluginCfg = AiPluginConfig(contextBudget = 100_000),
            aiRuleConfig = AiRuleConfig(maxDiffCharsTotal = 1_000_000),
            model = "m",
        )
        val json = KarandashJson.Default
            .encodeToJsonElement(
                BudgetPlanDump.serializer(),
                ChunkBudget(buildTestRegistry()).ledger(input, NoOpLogger)
            ).jsonObject

        assertEquals("derived: sum of needs", json.getValue("ceilingSource").jsonPrimitive.content)
        val rules = json.getValue("rules").jsonArray.associateBy {
            it.jsonObject
                .getValue("rule")
                .jsonPrimitive.content
        }
        assertEquals(setOf(AiRuleIds.REVIEW_COMMENTS, AiRuleIds.CHECKS), rules.keys)
        rules.values.forEach { rule ->
            assertTrue(rule.jsonObject.keys.containsAll(FIELDS), "missing ${FIELDS - rule.jsonObject.keys}")
        }
        val review = rules.getValue(AiRuleIds.REVIEW_COMMENTS).jsonObject
        val sources = review.getValue("sources").jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals("config:ai.context.budget", sources["context"])
        assertEquals("derived: ai.max-token-budget split", sources["share"])
        assertEquals("derived", sources["chunkChars"])
        assertTrue(
            review
                .getValue("calls")
                .jsonPrimitive.content
                .toInt() > 1
        )
    }

    @Test
    fun `a file cut to its hunks and one cut by the running total appear in the cuts`() {
        val windowed = (1..3).joinToString("\n") { hunk(it) }
        val short = hunk(1)
        val mr = createTestMergeRequest(changes = listOf(change("A.kt", windowed), change("B.kt", short)))
        val config = AiRuleConfig(maxDiffCharsTotal = TOTAL, maxDiffCharsPerFile = PER_FILE)
        val prepared = DiffContextBuilder.build(mr, config, null).preparedDiff

        val cuts = DiffCuts
            .of(mr, prepared, DiffCuts.Options(DiffMode.CHANGED, DiffPreprocessorOptions(), false, PER_FILE))
            .associateBy { it.file }

        val a = cuts.getValue("A.kt")
        assertEquals(DiffCuts.HUNK_WINDOW, a.how)
        assertEquals(windowed.length, a.charsBefore)
        assertEquals(3, a.hunksBefore)
        assertTrue(a.charsAfter in 1 until a.charsBefore && a.hunksAfter < 3, "$a")
        val b = cuts.getValue("B.kt")
        assertEquals(DiffCuts.RUNNING_TOTAL, b.how)
        assertEquals(short.length, b.charsBefore)
        assertTrue(b.charsAfter < b.charsBefore, "$b")
    }

    @Test
    fun `a file sent whole is no cut`() {
        val mr = createTestMergeRequest(changes = listOf(change("A.kt", hunk(1))))
        val prepared = DiffContextBuilder.build(mr, AiRuleConfig(), null).preparedDiff

        assertEquals(emptyList(), DiffCuts.of(mr, prepared, DiffCuts.Options(DiffMode.CHANGED, DiffPreprocessorOptions(), false, PER_FILE)))
    }

    private fun hunk(line: Int) = "@@ -$line,0 +$line,1 @@\n+" + "a".repeat(HUNK_CHARS)

    private fun change(
        path: String,
        diff: String,
    ) = FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = diff)

    private companion object {
        const val SHARE = 10_000
        const val HUNK_CHARS = 200
        const val PER_FILE = 300
        const val TOTAL = 400
        val FIELDS = setOf(
            "context",
            "share",
            "t0",
            "reserve",
            "call",
            "room",
            "chunkChars",
            "calls",
            "need",
            "priority",
            "grant",
            "sources",
        )
    }
}
