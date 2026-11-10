package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.plugins.ai.core.diagnostics.ContextQualityDiagnostics
import io.karandash.plugins.ai.core.rules.AiContextQualityConfig
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.TruncationConfig
import io.karandash.plugins.ai.core.rules.TruncationStrategy
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiffContextBuilderPriorityTest {
    private fun mrWith(vararg changes: FileChange): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "t",
            description = null,
            author = User(username = "u", name = "U"),
            sourceBranch = "feat",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            changes = changes.toList(),
            commits = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private fun change(
        path: String,
        additions: Int,
        deletions: Int,
        diff: String
    ) = FileChange(path = path, changeType = ChangeType.MODIFIED, additions = additions, deletions = deletions, diffContent = diff)

    private val priorityConfig = AiContextQualityConfig(
        truncation = TruncationConfig(strategy = TruncationStrategy.PRIORITY),
    )
    private val aiConfig = AiRuleConfig()

    @Test
    fun `priority strategy reorders by file type - kotlin before lock`() {
        val kotlinDiff = """
            @@ -1,1 +1,2 @@
             line
            +code
        """.trimIndent()
        val lockDiff = """
            @@ -1,1 +1,2 @@
             pkg
            +entry
        """.trimIndent()
        val mr = mrWith(
            // Listed lock file first to prove the strategy, not input order, drives output.
            change("yarn.lock", additions = 1, deletions = 0, diff = lockDiff),
            change("src/Main.kt", additions = 1, deletions = 0, diff = kotlinDiff),
        )
        val ctx = DiffContextBuilder.build(mr, aiConfig, priorityConfig)
        val kotlinIdx = ctx.preparedDiff.indexOf("src/Main.kt")
        val lockIdx = ctx.preparedDiff.indexOf("yarn.lock")
        assertTrue(kotlinIdx >= 0, "Kotlin file missing from priority output: ${ctx.preparedDiff}")
        assertTrue(lockIdx > kotlinIdx, "Expected Kotlin file before lock file in priority output")
    }

    @Test
    fun `sequential strategy preserves input order`() {
        val lockDiff = """
            @@ -1,1 +1,2 @@
             pkg
            +entry
        """.trimIndent()
        val kotlinDiff = """
            @@ -1,1 +1,2 @@
             line
            +code
        """.trimIndent()
        val mr = mrWith(
            change("yarn.lock", additions = 1, deletions = 0, diff = lockDiff),
            change("src/Main.kt", additions = 1, deletions = 0, diff = kotlinDiff),
        )
        val sequentialCfg = AiContextQualityConfig(
            truncation = TruncationConfig(strategy = TruncationStrategy.SEQUENTIAL),
        )
        val ctx = DiffContextBuilder.build(mr, aiConfig, sequentialCfg)
        // SEQUENTIAL keeps input order: lock file appears first.
        val lockIdx = ctx.preparedDiff.indexOf("yarn.lock")
        val kotlinIdx = ctx.preparedDiff.indexOf("src/Main.kt")
        assertTrue(lockIdx >= 0 && kotlinIdx > lockIdx)
    }

    @Test
    fun `two-arg overload matches SEQUENTIAL order`() {
        val lockDiff = """
            @@ -1,1 +1,2 @@
             pkg
            +entry
        """.trimIndent()
        val kotlinDiff = """
            @@ -1,1 +1,2 @@
             line
            +code
        """.trimIndent()
        val mr = mrWith(
            change("yarn.lock", additions = 1, deletions = 0, diff = lockDiff),
            change("src/Main.kt", additions = 1, deletions = 0, diff = kotlinDiff),
        )
        val ctx = DiffContextBuilder.build(mr, aiConfig)
        val lockIdx = ctx.preparedDiff.indexOf("yarn.lock")
        val kotlinIdx = ctx.preparedDiff.indexOf("src/Main.kt")
        assertTrue(lockIdx >= 0 && kotlinIdx > lockIdx)
    }

    @Test
    fun `disabled contextQuality falls back to SEQUENTIAL order`() {
        val lockDiff = """
            @@ -1,1 +1,2 @@
             pkg
            +entry
        """.trimIndent()
        val kotlinDiff = """
            @@ -1,1 +1,2 @@
             line
            +code
        """.trimIndent()
        val mr = mrWith(
            change("yarn.lock", additions = 1, deletions = 0, diff = lockDiff),
            change("src/Main.kt", additions = 1, deletions = 0, diff = kotlinDiff),
        )
        val disabled = AiContextQualityConfig(
            enabled = false,
            truncation = TruncationConfig(strategy = TruncationStrategy.PRIORITY),
        )
        val ctx = DiffContextBuilder.build(mr, aiConfig, disabled)
        val lockIdx = ctx.preparedDiff.indexOf("yarn.lock")
        val kotlinIdx = ctx.preparedDiff.indexOf("src/Main.kt")
        assertTrue(lockIdx >= 0 && kotlinIdx > lockIdx)
    }

    @Test
    fun `empty changes produce empty prepared diff`() {
        val mr = mrWith()
        val ctx = DiffContextBuilder.build(mr, aiConfig, priorityConfig)
        assertEquals("", ctx.preparedDiff)
    }

    private val mixedDiff = """
        @@ -1,2 +1,2 @@
        -removedLine
        +addedLine
         kept
    """.trimIndent()

    // The preprocessor strips `-` lines by default; keep them so reviewMode is what decides.
    private val keepDeletions = aiConfig.copy(diffPreprocessor = DiffPreprocessorOptions(stripDeletedLines = false))

    @Test
    fun `reviewMode added_only strips removed lines on both truncation paths`() {
        val mr = mrWith(change("src/Main.kt", additions = 1, deletions = 1, diff = mixedDiff))
        for (quality in listOf(null, priorityConfig)) {
            val prepared = DiffContextBuilder.build(mr, keepDeletions, quality, reviewMode = DiffMode.ADDED_ONLY).preparedDiff
            assertTrue(prepared.contains("addedLine"), prepared)
            assertFalse(prepared.contains("removedLine"), prepared)
        }
    }

    @Test
    fun `reviewMode full sends new file content on both truncation paths`() {
        val mr = mrWith(change("src/Main.kt", 1, 1, mixedDiff).copy(newContent = "whole new file"))
        for (quality in listOf(null, priorityConfig)) {
            val prepared = DiffContextBuilder.build(mr, keepDeletions, quality, reviewMode = DiffMode.FULL).preparedDiff
            assertTrue(prepared.contains("whole new file"), prepared)
            assertFalse(prepared.contains("removedLine"), prepared)
        }
    }

    @Test
    fun `default reviewMode keeps the full diff`() {
        val mr = mrWith(change("src/Main.kt", additions = 1, deletions = 1, diff = mixedDiff))
        for (quality in listOf(null, priorityConfig)) {
            val prepared = DiffContextBuilder.build(mr, keepDeletions, quality).preparedDiff
            assertTrue(prepared.contains("removedLine"), prepared)
        }
    }

    private fun configFrom(contextQuality: String): AiRuleConfig =
        AiRuleConfig.fromPluginConfig(Json.parseToJsonElement("""{"contextQuality": $contextQuality}"""))

    private val threeFiles = mrWith(
        change("src/A.kt", 1, 0, "@@ -1,1 +1,2 @@\n a\n+big change"),
        change("yarn.lock", 1, 0, "@@ -1,1 +1,2 @@\n b\n+lock"),
        change("src/C.kt", 1, 0, "@@ -1,1 +1,2 @@\n c\n+other"),
    )

    /** `ai.contextQuality.maxFilesToAnalyze` keeps the highest-priority files and omits the rest. */
    @Test
    fun `maxFilesToAnalyze sends only the highest-priority files`() {
        val config = configFrom("""{"maxFilesToAnalyze": 2}""")
        val ctx = DiffContextBuilder.build(threeFiles, config, config.contextQuality)
        assertTrue("(+) big change" in ctx.preparedDiff && "(+) other" in ctx.preparedDiff, ctx.preparedDiff)
        assertFalse("(+) lock" in ctx.preparedDiff, ctx.preparedDiff)
        assertEquals(1, ctx.omittedFileCount)
    }

    @Test
    fun `unset maxFilesToAnalyze sends every file`() {
        val config = configFrom("{}")
        val ctx = DiffContextBuilder.build(threeFiles, config, config.contextQuality)
        assertTrue("(+) lock" in ctx.preparedDiff, ctx.preparedDiff)
        assertEquals(0, ctx.omittedFileCount)
    }

    /** The `budget=~N tokens` figure the truncation decision of `src/A.kt` reports under [estimator]. */
    private fun reportedTokens(estimator: String): Int {
        val debug = mutableListOf<String>()
        val logger = object : io.karandash.logger.Logger {
            override fun debug(message: String) {
                debug += message
            }

            override fun info(message: String) = Unit

            override fun warn(message: String) = Unit

            override fun error(message: String) = Unit
        }
        val config = configFrom("""{"truncation": {"strategy": "priority", "tokenEstimator": "$estimator"}}""")
        DiffContextBuilder.build(threeFiles, config, config.contextQuality, diagnostics = ContextQualityDiagnostics(logger))
        return debug
            .first { it.startsWith("truncation-decision: src/A.kt") }
            .substringAfter("budget=~")
            .substringBefore(" ")
            .toInt()
    }

    /** `ai.contextQuality.truncation.tokenEstimator` converts the per-file budget it reports into tokens. */
    @Test
    fun `tokenEstimator chars3 reports a third of the char budget as tokens`() {
        val chars4 = reportedTokens("chars4")
        val chars3 = reportedTokens("chars3")
        // Same char budget both times: 3 * chars3 and 4 * chars4 differ only by the integer division.
        assertTrue(chars3 > chars4 && kotlin.math.abs(chars3 * 3 - chars4 * 4) < 4, "chars4=$chars4 chars3=$chars3")
    }
}
