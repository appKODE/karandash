package io.karandash.plugins.ai

import io.karandash.api.finding.ReviewCoverage
import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.models.finding.Severity
import io.karandash.models.finding.inLanguage
import io.karandash.sdk.NoOpLogger
import io.karandash.translations.polish.PolishTranslationModule
import io.karandash.translations.russian.RussianTranslationModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiStatusLineTest {
    private fun failed(id: String) = SkippedRuleInfo(id, "provider error: timed out", SkipCategory.AI_PROVIDER_ERROR)

    @Test
    fun `every call failed reads unavailable`() {
        val state = AiRuleState(ruleSkipReasons = mapOf("a" to failed("a"), "b" to failed("b")))
        assertEquals("AI: unavailable — 2/2 calls failed", state.aiStatus(configured = true))
    }

    @Test
    fun `some calls failed reads partly unavailable`() {
        val state = AiRuleState(ruleModels = mapOf("a" to "m"), ruleSkipReasons = mapOf("b" to failed("b")))
        assertEquals("AI: partly unavailable — 1/2 calls failed", state.aiStatus(configured = true))
    }

    @Test
    fun `answered calls read ok with rules and tokens`() {
        val state = AiRuleState(ruleModels = mapOf("a" to "m", "b" to "m"), ruleTokenCounts = mapOf("a" to 20_000, "b" to 3_100))
        assertEquals("AI: ok — 2 rules, 23.1k tokens", state.aiStatus(configured = true))
    }

    @Test
    fun `a policy skip names the policy`() {
        val skip = SkippedRuleInfo("a", "disabled by policy 'docs'", SkipCategory.DISABLED_BY_POLICY)
        assertEquals("AI: off by policy 'docs'", AiRuleState(engineSkips = mapOf("a" to skip)).aiStatus(configured = true))
    }

    @Test
    fun `a cache only run says so`() {
        assertEquals("AI: 3 findings from cache", AiRuleState(cachedFindings = 3).aiStatus(configured = true))
    }

    @Test
    fun `nothing ran reads not run and AI off prints nothing`() {
        assertEquals("AI: not run", AiRuleState().aiStatus(configured = true))
        assertNull(AiRuleState().aiStatus(configured = false))
    }

    @Test
    fun `a health finding appears only when a call failed`() {
        assertNull(healthFinding(AiHealth(calls = 3, failed = 0), NoOpLogger))
        val finding = checkNotNull(healthFinding(AiHealth(calls = 12, failed = 12), NoOpLogger))
        assertEquals(AI_HEALTH_RULE, finding.rule)
        assertEquals(Severity.INFO, finding.severity)
        assertEquals("AI unavailable (12/12 calls failed): the review is incomplete, see the job log", finding.message)
    }

    @Test
    fun `the health finding says why the provider failed and what to check`() {
        val finding = checkNotNull(
            healthFinding(AiHealth(calls = 2, failed = 2, reason = "provider error: unreachable: host (connect timeout)"), NoOpLogger),
        )
        assertEquals(
            "AI provider call failed (unreachable: host (connect timeout)); rerun the job when the provider is back, " +
                "or check the ai.provider and proxy settings",
            finding.suggestion,
        )
    }

    @Test
    fun `token text rounds to tenths of a thousand`() {
        assertEquals("950", tokenText(950))
        assertEquals("1.0k", tokenText(1_000))
        assertEquals("23.1k", tokenText(23_149))
    }

    @Test
    fun `the status line coverage and health finding render in the report language`() {
        RussianTranslationModule.register()
        PolishTranslationModule.register()
        val state = AiRuleState(ruleSkipReasons = mapOf("a" to failed("a"), "b" to failed("b")))
        val provenance = checkNotNull(state.provenance(state.aiStatusLine(configured = true)))
        assertEquals("AI: unavailable — 2/2 calls failed", provenance.aiStatus)
        assertEquals("AI: unavailable — 2/2 calls failed", provenance.aiStatusIn(null))
        assertEquals("AI: недоступен — не удалось вызовов: 2/2", provenance.aiStatusIn("ru"))
        assertEquals("AI: niedostępne — nieudanych wywołań: 2/2", provenance.aiStatusIn("pl"))
        val ok = AiRuleState(ruleModels = mapOf("a" to "m"), ruleTokenCounts = mapOf("a" to 950))
        assertEquals("AI: ок — правил: 1, токенов: 950", ok.provenance(ok.aiStatusLine(configured = true))?.aiStatusIn("ru"))

        val coverage = ReviewCoverage(totalFiles = 3, reviewedFiles = 2, chunksSkipped = 2, dropped = listOf("B.kt"))
        assertEquals("reviewed 2/3 files, 2 chunks skipped, 0 truncated, left out: B.kt", coverage.describe())
        assertEquals("проверено файлов: 2/3, пропущено 2 фрагмента, обрезано: 0, не вошло: B.kt", coverage.describe("ru"))

        val finding = checkNotNull(healthFinding(AiHealth(calls = 12, failed = 12), NoOpLogger))
        assertEquals(
            "AI недоступен (не удалось вызовов: 12/12): ревью неполное, подробности в логе задачи",
            finding.inLanguage("ru").message,
        )
    }
}
