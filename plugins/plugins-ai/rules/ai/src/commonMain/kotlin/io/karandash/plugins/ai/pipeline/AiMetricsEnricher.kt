package io.karandash.plugins.ai.pipeline

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.config.AiPipelineConfig
import io.karandash.plugins.ai.core.formatDecimals
import io.karandash.plugins.ai.core.metrics.ChangeEntropyCalculator
import io.karandash.plugins.ai.core.metrics.CognitiveLoadEstimator
import io.karandash.plugins.ai.core.metrics.CognitiveRiskLevel
import io.karandash.plugins.ai.core.metrics.HotspotAnalyzer
import io.karandash.plugins.ai.core.metrics.HotspotLevel
import io.karandash.plugins.ai.core.metrics.NaturalnessScorer
import io.karandash.plugins.ai.core.metrics.NcdCloneDetector
import io.karandash.sdk.Logger
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Enriches the finding list with metrics-based findings.
 *
 * Computes cognitive load, change entropy, hotspot analysis, clone detection,
 * and naturalness scoring, adding informational findings when thresholds are exceeded.
 */
internal class AiMetricsEnricher(
    private val pipelineConfig: AiPipelineConfig,
    private val logger: Logger,
) {
    /**
     * Computes enabled metrics and appends threshold-exceeding results as findings. The top-level
     * `ai.cloneDetection` / `ai.naturalness` blocks in [options] enable their metric too.
     */
    fun enrich(
        findings: List<Finding>,
        mr: MergeRequest,
        enabledMetrics: Set<String>,
        options: AiMetricsOptions = AiMetricsOptions(),
    ): List<Finding> {
        val enabled = enabledMetrics +
            listOfNotNull(
                CLONE_DETECTION.takeIf { options.cloneDetection?.enabled == true },
                NATURALNESS.takeIf { options.naturalness?.enabled == true },
                HOTSPOT.takeIf { HOTSPOT_ANALYSIS in enabledMetrics },
            )
        if (enabled.isEmpty()) return findings
        val metaFindings = mutableListOf<Finding>()

        enrichCognitiveLoad(metaFindings, mr, enabled, options)
        val entropy = enrichChangeEntropy(metaFindings, mr, enabled)
        enrichHotspot(metaFindings, mr, enabled, options)
        enrichCloneDetection(metaFindings, mr, enabled, options.cloneDetection)
        enrichNaturalness(metaFindings, mr, enabled, options.naturalness)

        if (metaFindings.isNotEmpty()) {
            logger.info("Metrics enrichment: ${metaFindings.size} metric finding(s) added")
        }

        val result = findings + metaFindings
        if (entropy == null || !options.changeEntropy.includeInMetadata) return result
        val value = entropy.formatDecimals(ENTROPY_DECIMALS)
        return result.map { it.copy(metadata = it.metadata + (CHANGE_ENTROPY_KEY to value)) }
    }

    private fun enrichCognitiveLoad(
        metaFindings: MutableList<Finding>,
        mr: MergeRequest,
        enabledMetrics: Set<String>,
        options: AiMetricsOptions,
    ) {
        if ("cognitive-load" !in enabledMetrics) return

        val cognitiveLoad = CognitiveLoadEstimator.estimate(mr)
        val high = cognitiveLoad.riskLevel == CognitiveRiskLevel.HIGH
        val showTime = options.cognitiveLoad.estimatedReviewTime
        if (!high && !showTime) return

        val minutes = cognitiveLoad.estimatedReviewMinutes.toInt()
        val base = if (high) cognitiveLoad.recommendation ?: "MR exceeds recommended review complexity" else null
        val timeNote = "Cognitive load ${cognitiveLoad.riskLevel.name}: estimated review time ~$minutes min"
        metaFindings.add(
            Finding(
                severity = if (high) Severity.WARNING else Severity.INFO,
                message = listOfNotNull(base, timeNote.takeIf { showTime }).joinToString(" — "),
                rule = "metrics:cognitive-load",
                metadata = mapOf(
                    "estimatedReviewMinutes" to minutes.toString(),
                    "totalLoc" to cognitiveLoad.totalLoc.toString(),
                    "riskLevel" to cognitiveLoad.riskLevel.name,
                ),
            ),
        )
    }

    private fun enrichChangeEntropy(
        metaFindings: MutableList<Finding>,
        mr: MergeRequest,
        enabledMetrics: Set<String>,
    ): Double? {
        if ("change-entropy" !in enabledMetrics) return null

        val entropy = ChangeEntropyCalculator.calculate(mr)
        if (entropy.normalizedEntropy <= pipelineConfig.thresholds.highEntropy.fraction) return entropy.normalizedEntropy

        metaFindings.add(
            Finding(
                severity = Severity.INFO,
                message = "Shotgun surgery detected: changes spread across ${entropy.fileCount} files in " +
                    "${entropy.directoryCount} directories (entropy: ${entropy.normalizedEntropy.formatDecimals(2)})",
                rule = "metrics:change-entropy",
                metadata = mapOf(
                    "fileEntropy" to entropy.fileEntropy.toString(),
                    "normalizedEntropy" to entropy.normalizedEntropy.toString(),
                ),
            ),
        )
        return entropy.normalizedEntropy
    }

    private fun enrichHotspot(
        metaFindings: MutableList<Finding>,
        mr: MergeRequest,
        enabledMetrics: Set<String>,
        options: AiMetricsOptions,
    ) {
        if (HOTSPOT !in enabledMetrics) return

        val hotspots = HotspotAnalyzer.analyze(
            mr,
            complexitySaturationLines = options.hotspot.complexityThreshold.toDouble(),
            churnSaturationLines = options.hotspot.churnThreshold.toDouble(),
        )
        val highHotspots = hotspots.filter { it.level == HotspotLevel.HIGH }
        if (highHotspots.isEmpty()) return

        metaFindings.add(
            Finding(
                severity = Severity.INFO,
                message = "Hotspot files detected: ${highHotspots.joinToString(", ") { it.path }} — high churn + complexity",
                rule = "metrics:hotspot",
            ),
        )
    }

    private fun enrichCloneDetection(
        metaFindings: MutableList<Finding>,
        mr: MergeRequest,
        enabledMetrics: Set<String>,
        options: CloneDetectionOptions?,
    ) {
        if (CLONE_DETECTION !in enabledMetrics) return

        val threshold = options?.threshold ?: CloneDetectionOptions.DEFAULT_THRESHOLD
        val clones = NcdCloneDetector.detect(mr, maxSimilarity = 1.0 - threshold)
        if (clones.isEmpty()) return

        val severity = if (options?.onDetection.equals(WARN, ignoreCase = true)) Severity.WARNING else Severity.INFO
        for (clone in clones.take(pipelineConfig.cloneDetection.maxFindings)) {
            metaFindings.add(
                Finding(
                    severity = severity,
                    message = "Potential code clone detected: ${clone.pathA} \u2194 ${clone.pathB} " +
                        "(similarity: ${((1.0 - clone.ncdScore) * PERCENT_MULTIPLIER).roundToInt()}%)",
                    rule = "metrics:clone-detection",
                ),
            )
        }
    }

    private fun enrichNaturalness(
        metaFindings: MutableList<Finding>,
        mr: MergeRequest,
        enabledMetrics: Set<String>,
        options: NaturalnessOptions?,
    ) {
        if (NATURALNESS !in enabledMetrics) return

        val naturalness = NaturalnessScorer.score(mr.changes)
        val surpriseThreshold = options?.surpriseThreshold
        val unnatural = naturalness.filter {
            if (surpriseThreshold != null) {
                1.0 - exp(-it.averageSurprise) > surpriseThreshold
            } else {
                it.highSurpriseTokenCount > pipelineConfig.thresholds.highSurpriseTokens
            }
        }
        if (unnatural.isEmpty()) return
        if (options?.flagUnusualPatterns == false) {
            logger.info("Naturalness: unusual code patterns in ${unnatural.joinToString(", ") { it.path }}")
            return
        }

        metaFindings.add(
            Finding(
                severity = Severity.INFO,
                message = "Unusual code patterns in: ${unnatural.joinToString(", ") { it.path }} — may warrant closer review",
                rule = "metrics:naturalness",
            ),
        )
    }

    companion object {
        private const val PERCENT_MULTIPLIER = 100
        private const val ENTROPY_DECIMALS = 2
        private const val CHANGE_ENTROPY_KEY = "changeEntropy"
        private const val HOTSPOT = "hotspot"
        private const val HOTSPOT_ANALYSIS = "hotspot-analysis"
        private const val CLONE_DETECTION = "clone-detection"
        private const val NATURALNESS = "naturalness"
        private const val WARN = "warn"
    }
}
