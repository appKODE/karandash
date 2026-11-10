package io.karandash.plugins.ai.dispatch

import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.core.AiStatePersistence
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.config.CanaryConfig
import io.karandash.plugins.ai.core.config.CanaryMetrics
import io.karandash.plugins.ai.core.config.CanaryRuleStats
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.updateAndGet
import kotlin.time.Clock

private typealias StatsByRule = Map<String, CanaryRuleStats>

/**
 * Collects the [CanaryMetrics] the canary promotion criteria are evaluated against: per-rule
 * sample count, stage start time and false-positive rate (findings matching a user dismissal),
 * loaded from and saved to [persistence] so they accumulate across runs. Without persistence
 * the metrics only cover the current process.
 */
internal class CanaryTracker(
    private val persistence: AiStatePersistence?,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val persisted by lazy { persistence?.loadCanaryStats().orEmpty() }
    private val stats = atomic<Map<String, CanaryRuleStats>?>(null)

    /** The metrics [ruleId] would have with [mrId] counted as a sample, without recording it. */
    fun metricsWith(
        ruleId: String,
        config: CanaryConfig,
        mrId: String = "",
    ): CanaryMetrics {
        val at = now()
        return withSample(current(stats.value ?: persisted, ruleId, config, at), mrId).metrics(at)
    }

    /**
     * Records [mrId] as one sample of [ruleId] under [config] and returns the metrics including it. An MR already
     * counted in this stage is not counted again; a blank [mrId] always counts.
     */
    fun recordSample(
        ruleId: String,
        config: CanaryConfig,
        mrId: String = "",
    ): CanaryMetrics {
        val at = now()
        val updated = mutate { all -> all + (ruleId to withSample(current(all, ruleId, config, at), mrId)) }
        return checkNotNull(updated[ruleId]) { "canary stats for $ruleId were just recorded" }.metrics(at)
    }

    private fun current(
        all: StatsByRule,
        ruleId: String,
        config: CanaryConfig,
        at: Long,
    ): CanaryRuleStats = all[ruleId]?.takeIf { it.stage == config.stage } ?: CanaryRuleStats(config.stage, at)

    private fun withSample(
        stats: CanaryRuleStats,
        mrId: String,
    ): CanaryRuleStats =
        when {
            mrId.isEmpty() -> stats.copy(sampleSize = stats.sampleSize + 1)

            mrId in stats.sampledMrs -> stats

            // limit: remembers the last MAX_SAMPLED_MRS ids only; a re-run on an older MR counts again
            else -> stats.copy(sampleSize = stats.sampleSize + 1, sampledMrs = (stats.sampledMrs + mrId).takeLast(MAX_SAMPLED_MRS))
        }

    /** Adds [findings] of a sampled [ruleId]; those matching a dismissal in [feedbackStore] count as false positives. */
    fun recordFindings(
        ruleId: String,
        findings: List<Finding>,
        feedbackStore: FeedbackStore,
    ) {
        if (findings.isEmpty() || stats.value?.containsKey(ruleId) != true) return
        val falsePositives = feedbackStore.filterFindings(findings).filteredCount
        mutate { all ->
            val current = all[ruleId] ?: return@mutate all
            val next = current.copy(
                findings = current.findings + findings.size,
                falsePositives = current.falsePositives + falsePositives,
            )
            all + (ruleId to next)
        }
    }

    /** Saves the observations if any rule was sampled. */
    fun persist() {
        val current = stats.value ?: return
        persistence?.saveCanaryStats(current)
    }

    private fun mutate(transform: (StatsByRule) -> StatsByRule): StatsByRule =
        checkNotNull(stats.updateAndGet { transform(it ?: persisted) }) { "canary stats are set by the update" }

    private companion object {
        const val MAX_SAMPLED_MRS = 1000
    }
}
