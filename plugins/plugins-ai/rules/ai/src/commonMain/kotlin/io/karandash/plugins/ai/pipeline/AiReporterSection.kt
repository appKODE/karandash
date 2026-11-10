package io.karandash.plugins.ai.pipeline

import io.karandash.config.keys.AiReporterKeys
import io.karandash.models.finding.Finding
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.platform.createDirectories
import io.karandash.platform.safeParent
import io.karandash.platform.writeText
import io.karandash.plugins.ai.core.reporters.FindingTransformer
import io.karandash.plugins.ai.core.reporters.modules.AiPredictionReportModule
import io.karandash.plugins.ai.core.reporters.modules.ComplianceEvidenceReportModule
import io.karandash.plugins.ai.core.reporters.modules.CrossPlatformConsistencyReportModule
import io.karandash.plugins.ai.core.reporters.modules.DeveloperGrowthReportModule
import io.karandash.plugins.ai.core.reporters.modules.DoraMetricsReportModule
import io.karandash.plugins.ai.core.reporters.modules.FlakyRuleReportModule
import io.karandash.plugins.ai.core.reporters.modules.PolicyDriftReportModule
import io.karandash.plugins.ai.core.reporters.modules.RiskHeatmapReportModule
import io.karandash.plugins.ai.core.reporters.modules.RoiReportModule
import io.karandash.plugins.ai.core.reporters.modules.SmartDigestReportModule
import io.karandash.plugins.ai.core.reporters.modules.TeamScorecardReportModule
import io.karandash.sdk.Logger
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * AI reports enabled by top-level `reporters.<name>` entries (`reporters: { smartDigest: true }`).
 *
 * An entry is on when it is `true` or an object without `enabled: false`; its object is the entry's options.
 */
internal class AiReporterSection(
    private val byName: Map<String, JsonObject>,
) {
    /** Report ids the entries turn on, as `ai.reports` would. */
    val reportIds: Set<String> get() = byName.keys.mapNotNull { REPORT_IDS[it] }.toSet() + transforms().map { it.reportId }

    /** Reports this section renders itself (not served by `ai.reports`), keyed by report id; [language] is `report.language`. */
    fun generate(
        findings: List<Finding>,
        language: String? = null,
    ): Map<String, String> =
        buildMap {
            byName[AiReporterKeys.CHECK_RUN_ANNOTATION]?.let {
                put(CheckRunAnnotationReport.REPORT_ID, CheckRunAnnotationReport.render(findings, it, language))
            }
            for (transform in transforms()) put(transform.reportId, FindingTransformer.transformAll(findings, transform.template))
        }

    /** Writes the first attachment of each entry's `report:<id>` finding to the entry's `output` file. */
    fun writeOutputs(
        reportFindings: List<Finding>,
        logger: Logger,
    ) {
        for ((reportId, output) in outputTargets()) {
            val content = reportFindings
                .firstOrNull { it.rule == "report:$reportId" }
                ?.attachments
                ?.firstOrNull()
                ?.content
            if (content == null) {
                logger.info("reporters: no $reportId report this run; $output not written")
                continue
            }
            writeFile(output, content, logger)
        }
    }

    private fun outputTargets(): List<Pair<String, String>> =
        byName.mapNotNull { (name, options) ->
            val output = (options[OUTPUT_KEY] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            REPORT_IDS[name]?.let { it to output }
        } + transforms().mapNotNull { transform -> transform.output?.let { transform.reportId to it } }

    /** `reporters.findingTransform.transforms[]` entries with a `name` and a `template`. */
    private fun transforms(): List<Transform> {
        val list = byName[AiReporterKeys.FINDING_TRANSFORM]?.get("transforms") as? JsonArray ?: return emptyList()
        return list.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val template = (obj["template"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            Transform("$TRANSFORM_REPORT_PREFIX$name", template, (obj[OUTPUT_KEY] as? JsonPrimitive)?.contentOrNull)
        }
    }

    /** Runtime holder for one transform entry; never serialized. */
    private class Transform(
        val reportId: String,
        val template: String,
        val output: String?,
    )

    companion object {
        private const val OUTPUT_KEY = "output"
        private const val ENABLED_KEY = "enabled"
        private const val TRANSFORM_REPORT_PREFIX = "transform-"

        /** Legacy report name of the annotated diff (generated by [AiReportGenerator]). */
        const val ANNOTATED_DIFF_HTML_REPORT = "annotated-diff-html"

        /** `reporters.<name>` to the report id it enables. */
        val REPORT_IDS: Map<String, String> = mapOf(
            AiReporterKeys.SMART_DIGEST to SmartDigestReportModule.REPORT_ID,
            AiReporterKeys.CHECK_RUN_ANNOTATION to CheckRunAnnotationReport.REPORT_ID,
            AiReporterKeys.COMPLIANCE_EVIDENCE to ComplianceEvidenceReportModule.REPORT_ID,
            AiReporterKeys.ANNOTATED_DIFF_HTML to ANNOTATED_DIFF_HTML_REPORT,
            AiReporterKeys.TEAM_SCORECARD to TeamScorecardReportModule.REPORT_ID,
            AiReporterKeys.DEVELOPER_GROWTH to DeveloperGrowthReportModule.REPORT_ID,
            AiReporterKeys.RISK_HEATMAP to RiskHeatmapReportModule.REPORT_ID,
            AiReporterKeys.DORA_METRICS to DoraMetricsReportModule.REPORT_ID,
            AiReporterKeys.ROI_CALCULATOR to RoiReportModule.REPORT_ID,
            AiReporterKeys.CROSS_PLATFORM_CONSISTENCY to CrossPlatformConsistencyReportModule.REPORT_ID,
            AiReporterKeys.FLAKY_RULE_DETECTOR to FlakyRuleReportModule.REPORT_ID,
            AiReporterKeys.POLICY_DRIFT_DETECTOR to PolicyDriftReportModule.REPORT_ID,
            AiReporterKeys.AI_PREDICTIONS to AiPredictionReportModule.REPORT_ID,
        )

        /** Reads the AI entries of the top-level `reporters:` block of [rawConfig] (the whole config). */
        fun from(rawConfig: JsonElement?): AiReporterSection {
            val reporters = (rawConfig as? JsonObject)?.get("reporters") as? JsonObject ?: return AiReporterSection(emptyMap())
            val entries = reporters.entries.mapNotNull { (key, value) ->
                val name = ConfigKeyNormalizer.kebabToCamelCase(key)
                if (name !in AiReporterKeys.ALL) return@mapNotNull null
                val options = when {
                    value is JsonPrimitive && value.booleanOrNull == true -> JsonObject(emptyMap())
                    value is JsonObject && (value[ENABLED_KEY] as? JsonPrimitive)?.booleanOrNull != false -> value
                    else -> null
                }
                options?.let { name to it }
            }
            return AiReporterSection(entries.toMap())
        }

        private fun writeFile(
            output: String,
            content: String,
            logger: Logger,
        ) {
            try {
                val path = Path(output)
                path.safeParent?.let { createDirectories(it) }
                path.writeText(content)
                logger.info("Wrote AI report to $output")
            } catch (e: IOException) {
                logger.warn("Could not write AI report to $output: ${e.message}")
            }
        }
    }
}
