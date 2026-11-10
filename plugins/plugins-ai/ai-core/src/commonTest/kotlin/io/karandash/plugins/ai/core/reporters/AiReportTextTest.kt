package io.karandash.plugins.ai.core.reporters

import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.architecture.ProvenanceChain
import io.karandash.plugins.ai.core.performance.PgoProfile
import io.karandash.plugins.ai.statistics.RunRecord
import io.karandash.translations.russian.RussianTranslationModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiReportTextTest {
    @BeforeTest
    fun registerRussianPack() {
        RussianTranslationModule.register()
    }

    private val stats = DigestStats(
        totalFindings = 3,
        errors = 1,
        warnings = 1,
        infos = 1,
        rulesTriggered = 2,
        topRules = listOf("title"),
        passRate = 0.5,
    )

    private val scorecard = TeamScorecard(
        compositeScore = 80,
        ruleScores = mapOf("title" to RuleScore("title", 80, 0.25, 2)),
        totalRuns = 4,
        totalFindings = 2,
    )

    private val heatmap = RiskHeatmap(
        files = listOf(HeatmapEntry("src/A.kt", 5, 1.0, 2)),
        directories = listOf(HeatmapEntry("src", 5, 1.0, 0)),
    )

    private val mr = MergeRequest(
        id = "1",
        iid = 1,
        title = "My MR",
        description = null,
        sourceBranch = "feat/x",
        targetBranch = "main",
        author = User(username = "dev", name = "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = emptyList(),
    )

    private val findings = listOf(Finding(severity = Severity.ERROR, message = "m", rule = "title"))

    @Test
    fun `the static reports render their fixed texts in Russian`() {
        val digest = SmartDigestGenerator(aiService = null, language = "ru")
        val developer = digest.staticSummary(stats, DigestAudience.DEVELOPER)
        assertTrue("## Итоги проверки" in developer, developer)
        assertTrue("Замечаний: 3 (ошибок: 1, предупреждений: 1, информационных: 1)" in developer, developer)
        assertTrue("Доля успешных: 50%" in digest.staticSummary(stats, DigestAudience.MANAGER))
        assertTrue("Всего замечаний: 3 (критических: 1)" in digest.staticSummary(stats, DigestAudience.SECURITY))

        val card = TeamScorecardGenerator.formatReport(scorecard, "ru")
        assertTrue("Общая оценка: 80/100" in card && "Запусков: 4 | Всего замечаний: 2" in card, card)
        val map = RiskHeatmapGenerator.formatReport(heatmap, "ru")
        assertTrue("### Самые рискованные файлы" in map && "- src: риск=5" in map, map)
        val html = AnnotatedDiffHtmlBuilder.build(mr, findings, "ru")
        assertTrue("<title>Отчёт Karandash: My MR</title>" in html && "замечаний: 1" in html, html)

        val all = listOf(developer, card, map, html).joinToString("\n")
        listOf("finding(s)", "Pass rate", "Hottest", "Composite Score", "Karandash Report").forEach {
            assertFalse(it in all, "English leak: $it")
        }
    }

    @Test
    fun `the static reports keep their English texts unchanged`() {
        val developer = SmartDigestGenerator(aiService = null).staticSummary(stats, DigestAudience.DEVELOPER)
        assertEquals(
            "## Validation Summary\n3 finding(s): 1 error(s), 1 warning(s), 1 info(s)\nTop rules: title\n",
            developer,
        )
        assertEquals(
            "## Risk Heatmap\n\n### Hottest Files\n| File | Risk Score | Findings |\n|------|-----------|----------|\n" +
                "| src/A.kt | ██████████ 5 | 2 |\n\n### Hottest Directories\n- src: risk=5\n",
            RiskHeatmapGenerator.formatReport(heatmap),
        )
        assertTrue("Composite Score: 80/100\nRuns: 4 | Total Findings: 2" in TeamScorecardGenerator.formatReport(scorecard))
        val html = AnnotatedDiffHtmlBuilder.build(mr, findings)
        assertTrue("<title>Karandash Report: My MR</title>" in html && "| 1 finding(s)</p>" in html, html)
    }

    private val dora =
        DoraMetrics(changeFailureRate = 0.25, deployFrequencyPerDay = 1.5, averageFindingsPerRun = 2.0, totalRuns = 4, failedRuns = 1)
    private val roi = RoiResult(
        totalFindings = 10,
        totalRuns = 4,
        minutesSaved = 90.0,
        hoursSaved = 1.5,
        costSavedUsd = 150.0,
        hourlyRateUsd = 100.0,
    )
    private val growth = DeveloperGrowthData(
        authorId = "dev",
        totalRuns = 4,
        averageFindingsPerRun = 2.0,
        improvementPercent = 30.0,
        trend = GrowthTrend.IMPROVING,
        persistentProblemRules = listOf("title"),
    )
    private val evidence = ComplianceEvidenceBuilder.build(findings, "", "mr-1", "2026-01-01T00:00:00Z")

    @Test
    fun `the history and compliance reports render their fixed texts in Russian`() {
        val doraText = DoraMetricsCalculator.formatReport(dora, "ru")
        assertTrue("## Метрики DORA (окно: 30 дн.)" in doraText && "- Всего запусков: 4 (неудачных: 1)" in doraText, doraText)
        val roiText = RoiCalculator.formatReport(roi, "ru")
        assertTrue("- Сэкономлено времени (оценка): 1.5 ч" in roiText && "ставке разработчика $100 в час" in roiText, roiText)
        val growthText = DeveloperGrowthReportGenerator.formatReport(growth, "ru")
        assertTrue("Тенденция: УЛУЧШЕНИЕ (изменение: 30%)" in growthText && "Постоянные проблемы: title" in growthText, growthText)
        val complianceText = ComplianceEvidenceBuilder.formatReport(evidence, "ru")
        assertTrue("| title | НЕ ПРОЙДЕНО | 1 | ОШИБКА |" in complianceText && "### Контрольный список" in complianceText, complianceText)

        val all = listOf(doraText, roiText, growthText, complianceText).joinToString("\n")
        listOf("Total runs", "ROI Report", "Trend:", "IMPROVING", "Checklist", "FAIL").forEach {
            assertFalse(it in all, "English leak: $it")
        }
    }

    @Test
    fun `the history and compliance reports keep their English texts unchanged`() {
        assertEquals(
            "## DORA Metrics (30-day window)\n- Change failure rate: 25%\n- Deploy frequency: 1.5/day\n" +
                "- Avg findings/run: 2.0\n- Total runs: 4 (1 failed)\n",
            DoraMetricsCalculator.formatReport(dora),
        )
        assertEquals(
            "## ROI Report\n- Runs analyzed: 4\n- Total findings caught: 10\n- Estimated time saved: 1.5 hours\n" +
                "- Estimated cost saved: \$150\n  (at \$100/hr developer rate)\n",
            RoiCalculator.formatReport(roi),
        )
        assertEquals(
            "## Developer Growth Report: dev\nTrend: IMPROVING (30% change)\nRuns: 4 | Avg findings/run: 2.0\nPersistent issues: title\n",
            DeveloperGrowthReportGenerator.formatReport(growth),
        )
        assertTrue("| title | FAIL | 1 | ERROR |" in ComplianceEvidenceBuilder.formatReport(evidence))
    }

    private fun runOf(findingsByRule: Map<String, Int>) =
        RunRecord(
            timestamp = 1_000_000L,
            mrId = null,
            totalFindings = findingsByRule.values.sum(),
            errorCount = 0,
            warningCount = 0,
            infoCount = 0,
            findingsByRule = findingsByRule,
        )

    private val alwaysFiring = List(10) { runOf(mapOf("title" to 1)) }
    private val drift = PolicyDriftReport(
        drifts = listOf(RuleDrift("title", RuleDriftType.REGRESSION, baselineRate = 0.2, currentRate = 0.6, deltaPercent = 40.0)),
        recentRuns = 5,
        baselineRuns = 5,
        hasRegression = true,
    )
    private val platforms = mapOf("gitlab" to List(3) { runOf(mapOf("title" to 5)) }, "github" to List(3) { runOf(emptyMap()) })
    private val predictions = listOf(RulePrediction("title", probability = 0.8, confidence = PredictionConfidence.HIGH))

    @Test
    fun `the trend reports render their fixed texts in Russian`() {
        val flaky = FlakyRuleDetector.formatReport(FlakyRuleDetector.detect(alwaysFiring, "ru"), "ru")
        assertTrue("[СРАБАТЫВАЕТ ВСЕГДА] title: Правило срабатывает в 100% запусков" in flaky, flaky)
        val driftText = PolicyDriftDetector.formatReport(drift, "ru")
        assertTrue("- [РЕГРЕССИЯ] title: ↑40% (20% → 60%)" in driftText && "**ВНИМАНИЕ: обнаружены регрессии" in driftText, driftText)
        val consistency = CrossPlatformConsistencyChecker.formatReport(CrossPlatformConsistencyChecker.compare(platforms, "ru"), "ru")
        assertTrue(
            "Согласовано: НЕТ — обнаружен дрейф" in consistency && "- ДРЕЙФ: gitlab: в среднем замечаний 5" in consistency,
            consistency
        )
        val predictionText = AiPredictionReporter.formatReport(predictions, "ru")
        assertTrue("| title | 80% | ВЫСОКАЯ |" in predictionText && "## Прогноз срабатывания правил" in predictionText, predictionText)

        val all = listOf(flaky, driftText, consistency, predictionText).joinToString("\n")
        listOf("ALWAYS_FIRES", "REGRESSION", "WARNING", "Consistent", "DRIFT", "findings", "HIGH", "Probability").forEach {
            assertFalse(it in all, "English leak: $it")
        }
    }

    @Test
    fun `the trend reports keep their English texts unchanged`() {
        assertEquals(
            "## Flaky Rule Analysis\nRules analyzed: 1 | Runs: 10\nIssues found: 1\n\n" +
                "- [ALWAYS_FIRES] title: Rule fires on 100% of runs — may be too sensitive\n",
            FlakyRuleDetector.formatReport(FlakyRuleDetector.detect(alwaysFiring)),
        )
        assertEquals(
            "## Policy Drift Analysis\nRecent: 5 runs | Baseline: 5 runs\n\n- [REGRESSION] title: ↑40% (20% → 60%)\n" +
                "\n**WARNING: Regressions detected — review rule thresholds.**\n",
            PolicyDriftDetector.formatReport(drift),
        )
        assertEquals(
            "## Cross-Platform Consistency\nPlatforms: gitlab, github\nConsistent: NO — drift detected\n\n" +
                "- DRIFT: gitlab avg 5 findings vs github avg 0 findings\n",
            CrossPlatformConsistencyChecker.formatReport(CrossPlatformConsistencyChecker.compare(platforms)),
        )
        assertEquals(
            "## Rule Failure Predictions\n| Rule | Probability | Confidence |\n|------|------------|------------|\n| title | 80% | HIGH |\n",
            AiPredictionReporter.formatReport(predictions),
        )
    }

    @Test
    fun `provenance and PGO reports render in Russian and stay as written in English`() {
        val chain = ProvenanceChain.build(emptyList(), "cfg", "mr", "1.0")
        val english = ProvenanceChain.formatReport(chain)
        assertTrue(
            english.startsWith("## Validation Provenance Chain\nChain hash: ${chain.chainHash}\nConfig hash: cfg\nMR snapshot hash: mr\n"),
            english,
        )
        val ru = ProvenanceChain.formatReport(chain, "ru")
        assertTrue(ru.startsWith("## Цепочка происхождения проверки\nХеш цепочки: "), ru)
        assertTrue("Хеш конфигурации: cfg" in ru && "Версия инструмента: 1.0" in ru && "Замечаний: 0" in ru, ru)
        assertFalse("hash" in ru || "Findings" in ru, ru)

        PgoProfile.reset()
        PgoProfile.enableProfiling()
        PgoProfile.recordPath("hot.path")
        val pgoEn = PgoProfile.formatReport()
        val pgoRu = PgoProfile.formatReport("ru")
        PgoProfile.reset()
        assertTrue(pgoEn.startsWith("## PGO Profile Report\nTotal path executions: 1\nUnique paths: 1\n\nTop hot paths:\n"), pgoEn)
        assertTrue(pgoRu.startsWith("## Отчёт профиля PGO\nВсего выполнений путей: 1\nУникальных путей: 1\n\nСамые горячие пути:\n"), pgoRu)
    }
}
