package io.karandash.plugins.ai.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Canary rule promotion pipeline: shadow → canary → enforce.
 *
 * Rules in `shadow` run but findings are not reported.
 * Rules in `canary` report findings but don't block.
 * Auto-promotion based on false-positive rate and sample size.
 */
private const val PERCENT_ALL = 100

object CanaryPromotionEvaluator {
    internal const val DEFAULT_MIN_SAMPLE_SIZE = 50
    internal const val DEFAULT_MAX_FALSE_POSITIVE_RATE = 0.10
    internal const val DEFAULT_AFTER_DAYS = 14

    /**
     * Evaluates whether the current [config] stage should be promoted based on [metrics].
     *
     * @param config The canary configuration including current stage and optional auto-promote rules.
     * @param metrics Observed runtime metrics for this rule.
     * @return The resulting [CanaryStage] after applying promotion logic.
     */
    fun evaluateStage(
        config: CanaryConfig,
        metrics: CanaryMetrics
    ): CanaryStage {
        return when (config.stage) {
            CanaryStage.SHADOW -> {
                val promo = config.autoPromote ?: return CanaryStage.SHADOW
                if (meetsPromotionCriteria(metrics, promo)) promo.promoteTo else CanaryStage.SHADOW
            }

            CanaryStage.CANARY -> {
                val promo = config.autoPromote ?: return CanaryStage.CANARY
                if (meetsPromotionCriteria(metrics, promo)) promo.promoteTo else CanaryStage.CANARY
            }

            CanaryStage.ENFORCE -> {
                CanaryStage.ENFORCE
            }
        }
    }

    /** Returns true if findings for this rule should be reported (not shadow). */
    fun shouldReport(stage: CanaryStage): Boolean = stage != CanaryStage.SHADOW

    /**
     * Returns true when the MR [mrId] falls inside the canary rollout of [ruleId]. Only the `canary` [stage] is
     * sampled; the pick is a stable hash of rule and MR, so re-running on the same MR gives the same answer.
     */
    fun inRollout(
        config: CanaryConfig,
        stage: CanaryStage,
        ruleId: String,
        mrId: String,
    ): Boolean {
        if (stage != CanaryStage.CANARY || config.percentOfMrs >= PERCENT_ALL) return true
        return bucket("$ruleId:$mrId") < config.percentOfMrs.coerceAtLeast(0)
    }

    /** FNV-1a over the UTF-16 chars, reduced to 0..99. */
    private fun bucket(key: String): Int {
        val hash = key.fold(FNV_OFFSET) { acc, c -> (acc xor c.code.toUInt()) * FNV_PRIME }
        return (hash % PERCENT_ALL.toUInt()).toInt()
    }

    private const val FNV_OFFSET = 2_166_136_261u
    private const val FNV_PRIME = 16_777_619u

    /** Returns true if findings for this rule should block the pipeline. */
    fun shouldBlock(stage: CanaryStage): Boolean = stage == CanaryStage.ENFORCE

    private fun meetsPromotionCriteria(
        metrics: CanaryMetrics,
        promo: AutoPromoteConfig
    ): Boolean =
        metrics.sampleSize >= promo.minSampleSize &&
            metrics.falsePositiveRate <= promo.maxFalsePositiveRate &&
            metrics.daysSinceStart >= promo.afterDays
}

/** The stage in the canary promotion pipeline. */
@Serializable
enum class CanaryStage {
    /** Rule runs silently; findings are never reported. */
    @SerialName("shadow")
    SHADOW,

    /** Rule reports findings but they do not block the pipeline. */
    @SerialName("canary")
    CANARY,

    /** Rule is fully enforced; findings block at their original severity. */
    @SerialName("enforce")
    ENFORCE,
}

/**
 * Configuration for a rule's canary promotion state.
 *
 * @param stage Current stage in the promotion pipeline.
 * @param autoPromote Optional criteria for automatic promotion to the next stage.
 * @param percentOfMrs Rollout share for the `canary` stage.
 */
@Serializable
data class CanaryConfig(
    val stage: CanaryStage = CanaryStage.ENFORCE,
    val autoPromote: AutoPromoteConfig? = null,
    /** In the `canary` stage, the share of MRs (0-100) the rule runs on; the rest skip it. */
    val percentOfMrs: Int = PERCENT_ALL,
)

/**
 * Criteria that trigger automatic promotion to the next stage.
 *
 * @param afterDays Minimum days the rule must have been running before promotion.
 * @param maxFalsePositiveRate Maximum observed false-positive rate to qualify.
 * @param minSampleSize Minimum number of evaluated MRs before promotion.
 * @param promoteTo The stage to promote to when all criteria are met.
 */
@Serializable
data class AutoPromoteConfig(
    val afterDays: Int = CanaryPromotionEvaluator.DEFAULT_AFTER_DAYS,
    val maxFalsePositiveRate: Double = CanaryPromotionEvaluator.DEFAULT_MAX_FALSE_POSITIVE_RATE,
    val minSampleSize: Int = CanaryPromotionEvaluator.DEFAULT_MIN_SAMPLE_SIZE,
    val promoteTo: CanaryStage = CanaryStage.CANARY,
)

/**
 * Runtime metrics collected for a rule under canary observation.
 *
 * @param sampleSize Number of MRs evaluated so far.
 * @param falsePositiveRate Fraction of findings flagged as false positives by users.
 * @param daysSinceStart Days elapsed since the rule entered this stage.
 */
@Serializable
data class CanaryMetrics(
    val sampleSize: Int = 0,
    val falsePositiveRate: Double = 0.0,
    val daysSinceStart: Int = 0,
)

/**
 * Canary observations for one rule, persisted across runs so promotion criteria can be met.
 *
 * @param stage The configured stage these observations belong to; a different stage starts over.
 * @param startedAtEpochMs When the rule was first observed in [stage].
 * @param sampleSize MRs the rule was evaluated on in [stage], each MR once.
 * @param findings Findings the rule produced, before feedback filtering.
 * @param falsePositives Findings matching a dismissal recorded by users (the feedback store).
 * @param sampledMrs The most recent MR ids counted in [sampleSize], so a re-run on the same MR is not a new sample.
 */
@Serializable
data class CanaryRuleStats(
    val stage: CanaryStage,
    val startedAtEpochMs: Long,
    val sampleSize: Int = 0,
    val findings: Int = 0,
    val falsePositives: Int = 0,
    val sampledMrs: List<String> = emptyList(),
) {
    /** Metrics for [CanaryPromotionEvaluator] as of [nowEpochMs]. */
    fun metrics(nowEpochMs: Long): CanaryMetrics =
        CanaryMetrics(
            sampleSize = sampleSize,
            falsePositiveRate = if (findings > 0) falsePositives.toDouble() / findings else 0.0,
            daysSinceStart = ((nowEpochMs - startedAtEpochMs) / MS_PER_DAY).toInt(),
        )

    companion object {
        private const val MS_PER_DAY = 86_400_000L
    }
}
