package io.karandash.plugins.ai.core

import io.karandash.api.context.EnrichmentContext
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.context.testCandidates
import io.karandash.plugins.ai.core.diagnostics.ContextQualityDiagnostics
import io.karandash.plugins.ai.core.rules.AiContextQualityConfig
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.BudgetAllocation
import io.karandash.plugins.ai.core.rules.PerRuleContextConfig
import io.karandash.plugins.ai.core.rules.PerRuleContextResolver
import io.karandash.plugins.ai.core.rules.TruncationConfig
import io.karandash.plugins.ai.core.rules.TruncationStrategy
import io.karandash.sdk.NoOpLogger
import io.karandash.utils.mr.HunkParser
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiContextQualityIntegrationTest {
    // ── PRIORITY truncation orders files by scorer rank ──────────────────────

    @Test
    fun `PRIORITY strategy assigns larger budget to high-volume files`() {
        val big = fileChange("Big.kt", diffContent = "+".repeat(500))
        val small = fileChange("Small.kt", diffContent = "+".repeat(10))
        val config = AiContextQualityConfig(
            truncation = TruncationConfig(
                strategy = TruncationStrategy.PRIORITY,
                budgetAllocation = BudgetAllocation.PROPORTIONAL,
            ),
        )
        val aiConfig = AiRuleConfig(maxDiffCharsTotal = 400, maxDiffCharsPerFile = 400)
        val mr = mrWith(listOf(big, small))
        val diags = ContextQualityDiagnostics(NoOpLogger)
        val ctx = DiffContextBuilder.build(mr, aiConfig, config, null, diags)
        // Big.kt should appear in the prepared diff (it has more volume)
        assertTrue(ctx.preparedDiff.contains("Big.kt"), "high-volume file must be in diff")
    }

    @Test
    fun `files that fit the total together reach the prompt whole whatever their scores`() {
        val ctxLines = (1..20).joinToString("") { " context line $it\n" }
        val hunk = { n: Int -> "@@ -$n,21 +$n,21 @@\n$ctxLines-old $n\n+new $n\n" }
        val big = fileChange("Big.kt", diffContent = (1..6).joinToString("") { hunk(it * 100) })
        val small = fileChange("Small.kt", diffContent = "@@ -0,0 +1,3 @@\n+a\n+b\n+c\n")
        val config = AiContextQualityConfig(
            truncation = TruncationConfig(strategy = TruncationStrategy.PRIORITY, budgetAllocation = BudgetAllocation.PROPORTIONAL),
        )
        val total = listOf(big, small).sumOf { checkNotNull(it.diffContent).length }
        val aiConfig = AiRuleConfig(maxDiffCharsTotal = total, maxDiffCharsPerFile = total)
        val ctx = DiffContextBuilder.build(mrWith(listOf(big, small)), aiConfig, config, null, ContextQualityDiagnostics(NoOpLogger))
        assertFalse(ctx.preparedDiff.contains("omitted"), ctx.preparedDiff)
        assertEquals(6, Regex("old \\d+").findAll(ctx.preparedDiff).count())
    }

    // ── FindingLocationValidator drops out-of-range findings ─────────────────

    @Test
    fun `FindingLocationValidator drops finding on unknown path`() {
        val mr = mrWith(listOf(fileChange("src/Real.kt", diffContent = "@@ -1,1 +1,2 @@\n+added line\n")))
        val hallucinated = finding(path = "src/DoesNotExist.kt", line = 1)
        val diags = ContextQualityDiagnostics(NoOpLogger)
        val result = FindingLocationValidator.validate(
            findings = listOf(hallucinated),
            mr = mr,
            ruleId = "test",
            strict = true,
            diagnostics = diags,
        )
        assertTrue(result.isEmpty(), "finding on unknown path must be dropped")
    }

    @Test
    fun `FindingLocationValidator keeps MR-level finding with null location`() {
        val mr = mrWith(listOf(fileChange("src/Real.kt", diffContent = "@@ -1,1 +1,1 @@\n+line\n")))
        val mrLevel = finding(path = null, line = null)
        val result = FindingLocationValidator.validate(
            findings = listOf(mrLevel),
            mr = mr,
            ruleId = "test",
            strict = true,
            diagnostics = ContextQualityDiagnostics(NoOpLogger),
        )
        assertEquals(1, result.size, "MR-level finding must be preserved")
    }

    @Test
    fun `FindingLocationValidator keeps finding on added line`() {
        val diff = "@@ -0,0 +1,3 @@\n+line one\n+line two\n+line three\n"
        val mr = mrWith(listOf(fileChange("src/A.kt", diffContent = diff)))
        val valid = finding(path = "src/A.kt", line = 2)
        val result = FindingLocationValidator.validate(
            findings = listOf(valid),
            mr = mr,
            ruleId = "test",
            strict = false,
            diagnostics = ContextQualityDiagnostics(NoOpLogger),
        )
        assertEquals(1, result.size, "finding on added line must be preserved")
    }

    // ── PerRuleContextResolver applies per-rule override ─────────────────────

    @Test
    fun `PerRuleContextResolver applies per-rule findingValidator override`() {
        val global = AiContextQualityConfig()
        val override = PerRuleContextConfig(
            findingValidator = io.karandash.plugins.ai.core.rules
                .FindingValidatorConfig(enabled = false),
        )
        val withOverride = global.copy(ruleContext = mapOf("my-rule" to override))
        val resolved = PerRuleContextResolver.resolveForRule("my-rule", withOverride)
        assertFalse(resolved.findingValidator.enabled, "per-rule override must disable validator")
    }

    @Test
    fun `PerRuleContextResolver returns global config for unknown rule`() {
        val global = AiContextQualityConfig()
        val resolved = PerRuleContextResolver.resolveForRule("nonexistent-rule", global)
        assertEquals(global, resolved)
    }

    @Test
    fun `PerRuleContextResolver normalises rule ID`() {
        val global = AiContextQualityConfig()
        val override = PerRuleContextConfig(
            findingValidator = io.karandash.plugins.ai.core.rules
                .FindingValidatorConfig(enabled = false),
        )
        val withOverride = global.copy(ruleContext = mapOf("my-rule" to override))
        // CamelCase and underscores should normalize to the same key
        val resolved = PerRuleContextResolver.resolveForRule("MY_RULE", withOverride)
        assertFalse(resolved.findingValidator.enabled, "normalised rule ID must match override")
    }

    // ── HunkParser + HunkAwareTruncator correctly trims hunks ───────────────

    @Test
    fun `HunkAwareTruncator keeps whole hunks within budget`() {
        val diff = "@@ -1,2 +1,3 @@\n context\n+added\n context\n@@ -10,2 +11,3 @@\n ctx\n+second add\n ctx\n"
        val result = HunkAwareTruncator.truncate(diffContent = diff, budget = 60)
        assertTrue(result.keptHunks > 0)
        assertFalse(result.output.isBlank())
    }

    @Test
    fun `HunkAwareTruncator returns empty result for null diff`() {
        val result = HunkAwareTruncator.truncate(diffContent = null, budget = 1000)
        assertTrue(result.output.isBlank())
        assertEquals(0, result.keptHunks)
    }

    // ── RelatedFileContextEnricher path heuristics ───────────────────────────

    @Test
    fun `testCandidates generates maven test path`() {
        val candidates = testCandidates("src/main/kotlin/io/example/Foo.kt")
        assertTrue(candidates.any { it.contains("src/test/") && it.endsWith("FooTest.kt") })
    }

    @Test
    fun `testCandidates generates python test path`() {
        val candidates = testCandidates("src/utils.py")
        assertTrue(candidates.any { it.contains("test_utils.py") })
    }

    // ── EnrichmentContext wires into FilePriorityScorer ──────────────────────

    @Test
    fun `FilePriorityScorer elevates hotspot file`() {
        val hotspot = fileChange("HotFile.kt", additions = 5)
        val cold = fileChange("ColdFile.kt", additions = 5)
        val freqJson = buildJsonObject {
            put("frequencies", buildJsonObject { put("HotFile.kt", 50) })
            put("period", "6 months ago")
        }
        val enrichment = EnrichmentContext(
            data = mapOf("local-git:file-frequency" to freqJson),
        )
        val scored = FilePriorityScorer.score(
            listOf(hotspot, cold),
            io.karandash.plugins.ai.core.rules
                .TruncationPriorityConfig(),
            enrichment,
        )
        assertNotNull(scored)
        // Hotspot should rank first
        assertEquals("HotFile.kt", scored.first().change.path)
    }

    // ── TokenBudget file-split underflow guard ──────────────────────────────────────

    @Test
    fun `TokenBudget file split handles budget smaller than MIN_FILE_BUDGET times N`() {
        val changes = (1..5).map { fileChange("File$it.kt", additions = 10) }
        val scored = FilePriorityScorer.score(
            changes,
            io.karandash.plugins.ai.core.rules
                .TruncationPriorityConfig(),
            null,
        )
        val diags = ContextQualityDiagnostics(NoOpLogger)
        val allocations = TokenBudget.splitAcrossFiles(
            scoredFiles = scored,
            totalBudget = 100, // far below MIN_FILE_BUDGET * 5 = 1000
            maxPerFile = 1000,
            allocation = BudgetAllocation.PROPORTIONAL,
            diagnostics = diags,
        )
        assertTrue(allocations.all { it.budget >= 0 }, "no negative budgets allowed")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun fileChange(
        path: String,
        additions: Int = 1,
        diffContent: String? = null,
    ) = FileChange(
        path = path,
        changeType = io.karandash.models.ChangeType.MODIFIED,
        additions = additions,
        deletions = 0,
        diffContent = diffContent,
    )

    private fun mrWith(changes: List<FileChange>) = io.karandash.testfixtures.createTestMergeRequest(changes = changes)

    private fun finding(
        path: String?,
        line: Int?
    ) = Finding(
        rule = "test-rule",
        message = "test finding",
        severity = Severity.INFO,
        location = if (path != null) {
            Location.FileRef(path = path, line = line)
        } else {
            null
        },
    )
}
