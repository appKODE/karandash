package io.karandash.plugins.ai.pipeline

import io.karandash.api.ModuleRegistry
import io.karandash.api.ai.ReportContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.finding.FindingHashChain
import io.karandash.api.support.rethrowCancellation
import io.karandash.crypto.sha256Hex
import io.karandash.engine.ReportEngine
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Attachment
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.architecture.ProvenanceChain
import io.karandash.plugins.ai.core.config.AiPipelineConfig
import io.karandash.plugins.ai.core.config.AiResearchConfig
import io.karandash.plugins.ai.core.config.NotificationRouter
import io.karandash.plugins.ai.core.formatDecimals
import io.karandash.plugins.ai.core.performance.PgoProfile
import io.karandash.plugins.ai.core.reporters.AiPredictionReporter
import io.karandash.plugins.ai.core.reporters.AnnotatedDiffHtmlBuilder
import io.karandash.plugins.ai.core.reporters.CheckRunAnnotationBuilder
import io.karandash.plugins.ai.core.reporters.ComplianceEvidenceBuilder
import io.karandash.plugins.ai.core.reporters.CrossPlatformConsistencyChecker
import io.karandash.plugins.ai.core.reporters.DeveloperGrowthReportGenerator
import io.karandash.plugins.ai.core.reporters.DigestAudience
import io.karandash.plugins.ai.core.reporters.DoraMetricsCalculator
import io.karandash.plugins.ai.core.reporters.FindingTransformer
import io.karandash.plugins.ai.core.reporters.FlakyRuleDetector
import io.karandash.plugins.ai.core.reporters.PolicyDriftDetector
import io.karandash.plugins.ai.core.reporters.RiskHeatmapGenerator
import io.karandash.plugins.ai.core.reporters.RoiCalculator
import io.karandash.plugins.ai.core.reporters.SmartDigestGenerator
import io.karandash.plugins.ai.core.reporters.TeamScorecardGenerator
import io.karandash.plugins.ai.core.reporters.runHistory
import io.karandash.plugins.ai.core.research.BetweennessCentrality
import io.karandash.plugins.ai.core.research.CognitiveComplexityDelta
import io.karandash.plugins.ai.core.research.FindingUsefulnessScorer
import io.karandash.plugins.ai.core.research.PageRankCalculator
import io.karandash.plugins.ai.core.research.RefactoringDetector
import io.karandash.plugins.ai.core.research.UsefulnessLevel
import io.karandash.plugins.ai.statistics.RunRecord
import io.karandash.sdk.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/**
 * Generates AI-specific reports from validation findings.
 *
 * Handles statistics-based reports, trend analysis, research metrics,
 * and specialized report formats (HTML diffs, compliance evidence, etc.).
 */
internal class AiReportGenerator(
    private val pipelineConfig: AiPipelineConfig,
    private val logger: Logger,
    private val history: List<RunRecord> = emptyList(),
) {
    /**
     * Generates all enabled report content as name-to-content pairs. [aiConfig] is the run's parsed `ai` block
     * (the pipeline passes the one it parsed at configure time); it is parsed from [config] only when not given.
     */
    @Suppress("TooGenericExceptionCaught") // boundary: report generation must not crash pipeline
    fun generate(
        findings: List<Finding>,
        mr: MergeRequest?,
        config: JsonElement?,
        mrId: String,
        enabledReports: Set<String>,
        aiConfig: AiPluginConfig? = resolveAiConfig(config),
    ): Map<String, String> {
        if (enabledReports.isEmpty()) return emptyMap()

        val reports = mutableMapOf<String, String>()

        val language = LanguagePromptOverlays.responseLanguageOf(config)
        generateCoreReports(reports, findings, enabledReports, language)
        generateDigestReports(reports, findings, enabledReports, language)
        generateStatisticsReports(reports, mr, enabledReports, language)
        generateComplianceReports(reports, findings, mr, config, mrId, enabledReports, language)
        generateDiffAndCustomReports(reports, findings, mr, aiConfig, enabledReports, language)
        generateTrendReports(reports, mr, enabledReports, language)
        generateResearchReports(reports, findings, mr, aiConfig, enabledReports, language)
        generateNotificationAndPgoReports(reports, findings, aiConfig, enabledReports, language)

        if (reports.isNotEmpty()) {
            logger.info("Generated ${reports.size} report(s): ${reports.keys.joinToString(", ")}")
        }

        return reports
    }

    /**
     * Runs enabled AI report/metric modules via [ReportEngine].
     */
    suspend fun runReportEngine(
        registry: ModuleRegistry,
        enabledReports: Set<String>,
        enabledMetrics: Set<String>,
        mr: MergeRequest?,
        config: JsonElement?,
        findings: List<Finding>,
    ): List<Finding> {
        if (mr == null) return emptyList()

        val reportModules = registry.aiReports().filter { it.reportId in enabledReports }
        val metricModules = registry.aiMetrics().filter { it.metricId in enabledMetrics }
        if (reportModules.isEmpty() && metricModules.isEmpty()) return emptyList()

        // enrichment is never passed to report modules; ReportEngine skips modules whose inputs are missing
        val context = ReportContext(
            findings = findings,
            mr = mr,
            enrichment = EnrichmentContext.EMPTY,
            config = config ?: JsonObject(emptyMap()),
            history = runHistory(history),
        )
        val engine = ReportEngine(reportModules, metricModules, logger)
        val result = engine.generate(context)

        val resultFindings = mutableListOf<Finding>()
        for (report in result.reports) {
            resultFindings.add(
                Finding(
                    severity = Severity.INFO,
                    message = report.formatted.take(DIGEST_LOG_LIMIT),
                    rule = "report:${report.reportId}",
                    attachments = listOf(
                        Attachment(
                            filename = "${report.reportId}.json",
                            content = report.data.toString(),
                            contentType = CONTENT_TYPE_JSON,
                        ),
                    ),
                ),
            )
        }
        for (metric in result.metrics) {
            resultFindings.add(
                Finding(
                    severity = Severity.INFO,
                    message = "${metric.metricId}: ${metric.value.formatDecimals(2)} ${metric.unit}",
                    rule = "metric:${metric.metricId}",
                    metadata = buildMap {
                        put("value", metric.value.toString())
                        put("unit", metric.unit)
                        val label = metric.label
                        if (label != null) put("label", label)
                    },
                ),
            )
        }
        return resultFindings
    }

    private fun generateCoreReports(
        reports: MutableMap<String, String>,
        findings: List<Finding>,
        enabledReports: Set<String>,
        language: String?,
    ) {
        if ("risk-heatmap" in enabledReports) {
            val heatmap = RiskHeatmapGenerator.generate(findings)
            reports["risk-heatmap"] = RiskHeatmapGenerator.formatReport(heatmap, language)
        }

        if (REPORT_CHECK_RUN_SUMMARY in enabledReports) {
            val summary = CheckRunAnnotationBuilder.summary(findings)
            reports[REPORT_CHECK_RUN_SUMMARY] =
                GeneratedReportText.CHECK_RUN_SUMMARY.of(language, summary.conclusion, summary.totalAnnotations)
        }
    }

    private fun generateDigestReports(
        reports: MutableMap<String, String>,
        findings: List<Finding>,
        enabledReports: Set<String>,
        language: String?,
    ) {
        val digestGen = SmartDigestGenerator(null, language = language)
        for (audience in DigestAudience.entries) {
            val reportName = "digest-${audience.name.lowercase()}"
            if (reportName !in enabledReports) continue
            reports[reportName] = digestGen.staticSummary(digestGen.computeStats(findings), audience)
        }
    }

    @Suppress("TooGenericExceptionCaught") // boundary: statistics reports must not fail pipeline
    private fun generateStatisticsReports(
        reports: MutableMap<String, String>,
        mr: MergeRequest?,
        enabledReports: Set<String>,
        language: String?,
    ) {
        val statsReports = setOf(REPORT_TEAM_SCORECARD, REPORT_DORA_METRICS, REPORT_ROI, REPORT_DEVELOPER_GROWTH)
        if (enabledReports.none { it in statsReports }) return

        try {
            val records = history
            if (records.isEmpty()) return

            if (REPORT_TEAM_SCORECARD in enabledReports) {
                val scorecard = TeamScorecardGenerator.generate(records)
                reports[REPORT_TEAM_SCORECARD] = TeamScorecardGenerator.formatReport(scorecard, language)
            }
            if (REPORT_DORA_METRICS in enabledReports) {
                val dora = DoraMetricsCalculator.calculate(records)
                reports[REPORT_DORA_METRICS] = DoraMetricsCalculator.formatReport(dora, language)
            }
            if (REPORT_ROI in enabledReports) {
                val roi = RoiCalculator.calculate(records)
                reports[REPORT_ROI] = RoiCalculator.formatReport(roi, language)
            }
            if (REPORT_DEVELOPER_GROWTH in enabledReports) {
                val authorId = mr?.author?.username
                if (authorId != null) {
                    val growth = DeveloperGrowthReportGenerator.generate(authorId, records)
                    reports[REPORT_DEVELOPER_GROWTH] = DeveloperGrowthReportGenerator.formatReport(growth, language)
                }
            }
        } catch (e: Exception) {
            // boundary: statistics reports are optional
            e.rethrowCancellation()
            logger.warn("Statistics-based reports skipped: ${e::class.simpleName}: ${e.message}")
            // best-effort: statistics-based reports must never fail the pipeline
        }
    }

    private fun generateComplianceReports(
        reports: MutableMap<String, String>,
        findings: List<Finding>,
        mr: MergeRequest?,
        config: JsonElement?,
        mrId: String,
        enabledReports: Set<String>,
        language: String?,
    ) {
        if ("compliance-evidence" in enabledReports && findings.isNotEmpty()) {
            val timestamp = Clock.System.now().toString()
            val evidence = ComplianceEvidenceBuilder.build(
                findings = findings,
                configSnapshot = "",
                mrId = mrId,
                timestamp = timestamp,
            )
            reports["compliance-evidence"] = ComplianceEvidenceBuilder.formatReport(evidence, language)
        }

        if (PROVENANCE_CHAIN_REPORT in enabledReports && findings.isNotEmpty()) {
            reports[PROVENANCE_CHAIN_REPORT] = ProvenanceChain.formatReport(provenance(findings, mr, config), language)
        }
    }

    private fun generateDiffAndCustomReports(
        reports: MutableMap<String, String>,
        findings: List<Finding>,
        mr: MergeRequest?,
        aiConfig: AiPluginConfig?,
        enabledReports: Set<String>,
        language: String?,
    ) {
        if ("annotated-diff-html" in enabledReports && mr != null && findings.isNotEmpty()) {
            reports["annotated-diff-html"] = AnnotatedDiffHtmlBuilder.build(mr, findings, language)
        }

        if ("custom-findings" in enabledReports) {
            val findingTemplate = aiConfig?.findingTemplate
            if (findingTemplate != null && findings.isNotEmpty()) {
                reports["custom-findings"] = FindingTransformer.transformAll(findings, findingTemplate)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // boundary: trend reports must not fail pipeline
    private fun generateTrendReports(
        reports: MutableMap<String, String>,
        mr: MergeRequest?,
        enabledReports: Set<String>,
        language: String?,
    ) {
        val trendReports = setOf(REPORT_CROSS_PLATFORM, REPORT_FLAKY_RULES, REPORT_POLICY_DRIFT, REPORT_RULE_PREDICTIONS)
        if (enabledReports.none { it in trendReports }) return

        try {
            val records = history
            if (REPORT_CROSS_PLATFORM in enabledReports &&
                records.size >= pipelineConfig.reports.minRecordsForCrossPlatform
            ) {
                val report = CrossPlatformConsistencyChecker.compare(mapOf("current" to records), language)
                reports[REPORT_CROSS_PLATFORM] = CrossPlatformConsistencyChecker.formatReport(report, language)
            }
            if (records.size >= pipelineConfig.reports.minRecordsRequired) {
                if (REPORT_FLAKY_RULES in enabledReports) {
                    val flakyReport = FlakyRuleDetector.detect(records, language)
                    if (flakyReport.issues.isNotEmpty()) {
                        reports[REPORT_FLAKY_RULES] = FlakyRuleDetector.formatReport(flakyReport, language)
                    }
                }
                if (REPORT_POLICY_DRIFT in enabledReports) {
                    val midpoint = records.size / 2
                    if (midpoint > 0) {
                        val baseline = records.take(midpoint)
                        val recent = records.drop(midpoint)
                        val driftReport = PolicyDriftDetector.detect(recent, baseline)
                        if (driftReport.drifts.isNotEmpty()) {
                            reports[REPORT_POLICY_DRIFT] = PolicyDriftDetector.formatReport(driftReport, language)
                        }
                    }
                }
            }
            if (REPORT_RULE_PREDICTIONS in enabledReports && mr != null) {
                val predictions = AiPredictionReporter.predict(mr, records)
                if (predictions.isNotEmpty()) {
                    reports[REPORT_RULE_PREDICTIONS] = AiPredictionReporter.formatReport(predictions, language)
                }
            }
        } catch (e: Exception) {
            // boundary: statistics reports are optional
            e.rethrowCancellation()
            logger.warn("Trend reports skipped: ${e::class.simpleName}: ${e.message}")
            // best-effort: trend reports must never fail the pipeline
        }
    }

    private fun generateResearchReports(
        reports: MutableMap<String, String>,
        findings: List<Finding>,
        mr: MergeRequest?,
        aiConfig: AiPluginConfig?,
        enabledReports: Set<String>,
        language: String?,
    ) {
        val mrChanges = mr?.changes ?: emptyList()
        if (mrChanges.isNotEmpty()) {
            val research = aiConfig?.research ?: AiResearchConfig()
            if ("pagerank-pillars" in enabledReports) {
                val pageRank = PageRankCalculator.calculate(mrChanges)
                val pillars = pageRank.filter { it.isArchitecturalPillar }
                if (pillars.isNotEmpty()) {
                    reports["pagerank-pillars"] = pillars.joinToString("\n") {
                        GeneratedReportText.PAGERANK_LINE.of(language, it.path, it.normalizedRank.formatDecimals(2))
                    }
                }
            }

            if ("betweenness-bottlenecks" in enabledReports) {
                val graph = PageRankCalculator.buildImportGraph(mrChanges)
                val centrality = BetweennessCentrality.calculate(graph, research.betweenness)
                val bottlenecks = centrality.filter { it.isBottleneck }
                if (bottlenecks.isNotEmpty()) {
                    reports["betweenness-bottlenecks"] = bottlenecks.joinToString("\n") {
                        GeneratedReportText.CENTRALITY_LINE.of(language, it.path, it.centrality.formatDecimals(3))
                    }
                }
            }

            if ("complexity-delta" in enabledReports) {
                val complexityDeltas = CognitiveComplexityDelta.compute(mrChanges, research.cognitiveComplexity)
                val totalDelta = CognitiveComplexityDelta.totalDelta(complexityDeltas)
                if (totalDelta > 0) {
                    reports["complexity-delta"] =
                        GeneratedReportText.COMPLEXITY_DELTA.of(language, totalDelta, complexityDeltas.count { it.increased })
                }
            }

            if (REPORT_REFACTORING_ANALYSIS in enabledReports) {
                val refactoring = RefactoringDetector.analyze(mrChanges)
                if (refactoring.isRefactoringOnly) {
                    reports[REPORT_REFACTORING_ANALYSIS] =
                        GeneratedReportText.REFACTORING_ONLY.of(language, refactoring.refactoringPatterns.size)
                } else if (refactoring.isMixed) {
                    reports[REPORT_REFACTORING_ANALYSIS] =
                        GeneratedReportText.REFACTORING_MIXED.of(
                            language,
                            refactoring.refactoringPatterns.size,
                            refactoring.behavioralChanges.size
                        )
                }
            }
        }

        if ("usefulness-analysis" in enabledReports && findings.isNotEmpty()) {
            val usefulnessScores = FindingUsefulnessScorer.scoreAll(findings)
            val notUseful = usefulnessScores.count { it.level == UsefulnessLevel.NOT_USEFUL }
            if (notUseful > 0) {
                reports["usefulness-analysis"] =
                    GeneratedReportText.NOT_USEFUL.of(language, notUseful, findings.size)
            }
        }
    }

    private fun generateNotificationAndPgoReports(
        reports: MutableMap<String, String>,
        findings: List<Finding>,
        aiConfig: AiPluginConfig?,
        enabledReports: Set<String>,
        language: String?,
    ) {
        if ("notification-routing" in enabledReports) {
            val notifRoutes = aiConfig?.notifications ?: emptyList()
            if (notifRoutes.isNotEmpty() && findings.isNotEmpty()) {
                val routed = NotificationRouter.route(findings, notifRoutes)
                reports["notification-routing"] = routed
                    .groupBy { listOfNotNull(it.target.channel, it.target.target.ifBlank { null }, it.target.url).joinToString(" ") }
                    .entries
                    .joinToString("\n") { GeneratedReportText.ROUTED.of(language, it.key, it.value.size) }
            }
        }

        if ("pgo-profile" in enabledReports &&
            aiConfig?.pgoEnabled == true &&
            PgoProfile.totalExecutions() > 0
        ) {
            reports["pgo-profile"] = PgoProfile.formatReport(language)
        }
    }

    companion object {
        /** Report id of the provenance chain (`ai.reports.provenance-chain`, `ai.architecture.provenanceChain`). */
        const val PROVENANCE_CHAIN_REPORT = "provenance-chain"

        /** The provenance chain of [findings], hashing [config] and [mr] as they were validated. */
        fun provenance(
            findings: List<Finding>,
            mr: MergeRequest?,
            config: JsonElement?,
        ): FindingHashChain =
            ProvenanceChain.build(
                findings = findings,
                configHash = config?.let { sha256Hex(it.toString()) } ?: UNKNOWN_PLACEHOLDER,
                mrSnapshotHash = mr?.let { sha256Hex(KarandashJson.Default.encodeToString(MergeRequest.serializer(), it)) }
                    ?: UNKNOWN_PLACEHOLDER,
                toolVersion = TOOL_VERSION,
            )

        private const val DIGEST_LOG_LIMIT = 300
        private const val UNKNOWN_PLACEHOLDER = "unknown"
        private const val TOOL_VERSION = "1.7.0"
        private const val REPORT_CHECK_RUN_SUMMARY = "check-run-summary"
        private const val REPORT_TEAM_SCORECARD = "team-scorecard"
        private const val REPORT_DORA_METRICS = "dora-metrics"
        private const val REPORT_ROI = "roi"
        private const val REPORT_DEVELOPER_GROWTH = "developer-growth"
        private const val REPORT_CROSS_PLATFORM = "cross-platform"
        private const val REPORT_FLAKY_RULES = "flaky-rules"
        private const val REPORT_POLICY_DRIFT = "policy-drift"
        private const val REPORT_RULE_PREDICTIONS = "rule-predictions"
        private const val REPORT_REFACTORING_ANALYSIS = "refactoring-analysis"
        private const val CONTENT_TYPE_JSON = "application/json"

        private fun resolveAiConfig(config: JsonElement?): AiPluginConfig? {
            val obj = config as? JsonObject ?: return null
            val aiBlock = obj["ai"] as? JsonObject ?: obj
            return AiPluginConfig.fromPluginConfig(
                AiPluginConfig.topLevelAiBlockToPluginConfigElement(aiBlock),
            )
        }
    }
}
