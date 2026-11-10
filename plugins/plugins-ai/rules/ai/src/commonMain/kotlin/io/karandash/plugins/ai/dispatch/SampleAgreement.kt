@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiService
import io.karandash.api.ai.AiTokenBudget
import io.karandash.api.run.dumpJson
import io.karandash.logger.Logger
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.ai.core.AiCostEstimator
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.ResponseCacheSalt
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.pipeline.AgreementConfig
import io.karandash.plugins.ai.core.pipeline.AiMetadataTransformer
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.time.Duration

/**
 * `ai.review-comments.samples`: review-comments asks the model [samples][io.karandash.plugins.ai.core.pipeline.ReviewSamplingConfig]
 * times per call and keeps what the samples agree on. Sample 1 is the normal call; the others repeat the same prompt
 * at `sample-temperature`, past the response cache (the provider's prompt-prefix cache still applies).
 *
 * Two comments of different samples agree ([matches]) on the same file, at most `ai.agreement.lineWindow` lines
 * apart, with the same category when both carry one, and an overlap coefficient of their messages' 4+ letter words of
 * at least `ai.agreement.minSimilarity`. An agreed comment keeps the earliest sample's version with `agreement=agreed`
 * and `samples=k/n`. One only a single sample reported follows `ai.agreement.singleton`: `demote` makes it an INFO
 * file-level note (`agreement=single`, never inline, no cap slot), `drop` drops it, `keep` keeps it as is.
 *
 * A later sample that fails, does not parse or is empty while sample 1 found something is warned and sample 1 is
 * returned unmarked: agreement never loses sample 1. Decisions are dumped as `ai/stages/agreement.json`.
 */
internal object SampleAgreement {
    const val AGREED = "agreed"
    const val SINGLE = "single"
    private const val MIN_WORD_LENGTH = 4
    private val WORD_SPLIT = Regex("[^\\p{L}\\p{Nd}]+")
    private const val SAMPLES_KEY = "ai.review-comments.samples"
    private val estimator = AiCostEstimator()

    /** One cluster of matching comments and what agreement made of it. */
    @Serializable
    data class Record(
        val rule: String,
        val file: String?,
        val line: Int?,
        val category: String?,
        val message: String,
        val samples: List<Int>,
        val similarity: List<Double>,
        val decision: String,
    )

    /** The merged findings and one record per comment cluster. */
    @Serializable
    data class Merged(
        val findings: List<Finding>,
        val records: List<Record>,
    )

    /** How many review-comments samples [input] takes: 1 for any other rule. */
    fun samplesOf(input: AiRuleDispatcher.DispatchInput): Int =
        if (input.ruleId == AiRuleIds.REVIEW_COMMENTS && input.aiService != null) input.pluginCfg.reviewSampling.samples else 1

    /**
     * Tokens the extra review-comments samples add to [held] tokens of [calls] calls of [ruleId]: each call's prompt
     * again at [model]'s cached-read price ([AiCostEstimator.cacheReadMultiplier]), its output [reserve] in full.
     */
    fun extraTokens(
        ruleId: String,
        pluginCfg: AiPluginConfig,
        model: String,
        held: Long,
        calls: Int,
        reserve: Int,
    ): Long {
        val extra = if (ruleId == AiRuleIds.REVIEW_COMMENTS) pluginCfg.reviewSampling.samples - 1 else 0
        if (extra <= 0) return 0
        val output = calls.toLong() * reserve
        val prompt = (held - output).coerceAtLeast(0) * estimator.cacheReadMultiplier(model)
        return (prompt.toLong() + output) * extra
    }

    /**
     * The extra samples of a rule's [calls], run after every first call has finished: [firsts] holds each call's
     * sample 1 and the tokens its extra samples need (0: none taken), null for a call not sent. The samples spend a
     * budget of their own, the rule's grant once per extra sample, so they never take tokens from the first pass:
     * a call whose samples that budget cannot hold keeps sample 1 unmarked, recorded in [skips] and told in one
     * INFO line per rule.
     */
    suspend fun samplePass(
        calls: List<AiRuleDispatcher.DispatchInput>,
        firsts: List<Pair<List<Finding>, Int>?>,
        skips: SkipLog,
        logger: Logger,
        sample: suspend (AiRuleDispatcher.DispatchInput, AiService, AiTokenBudget) -> List<Finding>,
    ): List<List<Finding>?> {
        val rule = calls.first()
        val grant = rule.budget.maxTokens.toLong() * (samplesOf(rule) - 1).coerceAtLeast(1)
        val budget = TokenBudget(grant.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val knob = budgetKnob(rule.pluginCfg)
        // Holds are taken in call order before any sample runs, so which calls get samples never depends on timing.
        val holds = firsts.map { first -> first?.second?.takeIf { it > 0 }?.let { BudgetReservation.open(budget, null, it) } }
        val refused = firsts.indices.filter { (firsts[it]?.second ?: 0) > 0 && holds[it] == null }
        refused.forEach { skips.addRefusal(BudgetRefusal(rule.ruleId, firsts[it]?.second ?: 0, knob, "extra samples")) }
        if (refused.isNotEmpty()) {
            logger.info(
                "[ai] rule=${rule.ruleId}: the sample budget held no extra samples for ${refused.size} of ${calls.size} calls; " +
                    "kept sample 1 without agreement there (raise $knob)",
            )
        }
        try {
            return mapParallel(firsts.indices.toList(), rule.pluginCfg.largeMr.maxParallel) { index ->
                val first = firsts[index]?.first
                val hold = holds[index]
                if (first == null || hold == null) return@mapParallel first
                val call = calls[index]
                call.concurrencySemaphore.withPermit {
                    val remaining = call.deadline?.let { -it.elapsedNow() } ?: Duration.INFINITE
                    withTimeoutOrNull(remaining) { hold.use { held -> sampled(call, first, logger) { sample(call, it, held) } } } ?: first
                }
            }
        } finally {
            holds.forEach { it?.close() }
            skips.addSampleTokens(budget.totalConsumed())
        }
    }

    /**
     * [first] merged with the extra samples of [input], each run by [sample] on the service it is given; [first]
     * itself when the rule takes one sample.
     */
    suspend fun sampled(
        input: AiRuleDispatcher.DispatchInput,
        first: List<Finding>,
        logger: Logger,
        sample: suspend (AiService) -> List<Finding>,
    ): List<Finding> {
        val n = samplesOf(input)
        val service = input.aiService
        if (n < 2 || service == null) return first
        val samples = mutableListOf(first)
        for (k in 2..n) {
            val probe = ConsensusRun.Probe()
            val sampleService = probe.watch(RuleTemperature.Service(service, input.pluginCfg.reviewSampling.sampleTemperature))
            val result = try {
                ResponseCacheSalt.with("sample$k") { sample(sampleService) }
            } catch (e: AiProviderException) {
                return unmarked(input.ruleId, first, "sample $k of $n failed (${e.message})", logger)
            }
            val failure = when {
                probe.failed -> "sample $k of $n failed (a model call failed)"
                result.any(::parseError) -> "sample $k of $n did not parse"
                result.none(::votes) && first.any(::votes) -> "sample $k of $n returned no comments"
                else -> null
            }
            if (failure != null) return unmarked(input.ruleId, first, failure, logger)
            samples += result
        }
        val merged = merge(samples, input.pluginCfg.agreement)
        dumpJson("ai/stages/agreement.json") { merged.records }
        return merged.findings
    }

    private fun unmarked(
        ruleId: String,
        first: List<Finding>,
        cause: String,
        logger: Logger,
    ): List<Finding> {
        logger.warn("[ai] rule=$ruleId: $cause; kept sample 1 without agreement ($SAMPLES_KEY)")
        return first
    }

    /** [samples] (sample 1 first) merged by agreement: sample 1's order, then comments only later samples made. */
    fun merge(
        samples: List<List<Finding>>,
        config: AgreementConfig,
    ): Merged {
        val clusters = mutableListOf<MutableList<Member>>()
        // sample 1's position -> its cluster: two equal comments of sample 1 are two clusters, decided apart
        val clusterOfFirst = mutableMapOf<Int, Int>()
        samples.forEachIndexed { s, findings ->
            findings.forEachIndexed { i, f ->
                if (!votes(f)) return@forEachIndexed
                val best = clusters
                    .filter { c -> c.none { it.sample == s } }
                    .mapNotNull { c -> similarity(c.first().finding, f, config)?.let { c to it } }
                    .maxByOrNull { it.second }
                if (best == null) {
                    clusters += mutableListOf(Member(s, f, 1.0))
                    if (s == 0) clusterOfFirst[i] = clusters.lastIndex
                } else {
                    best.first += Member(s, f, best.second)
                }
            }
        }
        val outcomes = clusters.map { c -> decide(c, samples.size, config) }
        // a demoted or dropped comment takes its paired code suggestion with it; later samples' suggestions never count
        val gone = clusters.filter { it.size == 1 && config.singleton != AgreementConfig.KEEP }.map { it.first().finding }
        val first = samples.first().mapIndexedNotNull { i, f ->
            val cluster = clusterOfFirst[i]
            when {
                cluster != null -> outcomes[cluster].finding
                f.rule == AiRuleIds.CODE_SUGGESTIONS && gone.any { AiMetadataTransformer.pairsWith(f, it) } -> null
                else -> f
            }
        }
        val later = clusters.indices.filter { clusters[it].first().sample > 0 }.mapNotNull { outcomes[it].finding }
        return Merged(first + later, clusters.mapIndexed { i, c -> record(c, outcomes[i].decision) })
    }

    private data class Member(
        val sample: Int,
        val finding: Finding,
        val similarity: Double,
    )

    private data class Outcome(
        val finding: Finding?,
        val decision: String,
    )

    private fun decide(
        cluster: List<Member>,
        n: Int,
        config: AgreementConfig,
    ): Outcome {
        val head = cluster.first().finding
        val tag = mapOf(AiFindingMetadataKeys.SAMPLES to "${cluster.size}/$n")
        if (cluster.size > 1) {
            return Outcome(head.copy(metadata = head.metadata + tag + (AiFindingMetadataKeys.AGREEMENT to AGREED)), AGREED)
        }
        val single = head.metadata + tag + (AiFindingMetadataKeys.AGREEMENT to SINGLE)
        return when (config.singleton) {
            AgreementConfig.DROP -> Outcome(null, AgreementConfig.DROP)
            AgreementConfig.KEEP -> Outcome(head.copy(metadata = single), AgreementConfig.KEEP)
            else -> Outcome(demoted(head, single, n), AgreementConfig.DEMOTE)
        }
    }

    /** [finding] as an INFO file-level note: its line moves to `llmLine`, so it is never posted inline. */
    private fun demoted(
        finding: Finding,
        metadata: Map<String, String>,
        n: Int,
    ): Finding {
        val ref = finding.location as? Location.FileRef
        val line = ref?.line?.let { mapOf(AiFindingMetadataKeys.LLM_LINE to it.toString()) }.orEmpty()
        return finding.copy(
            severity = Severity.INFO,
            location = ref?.copy(line = null, endLine = null) ?: finding.location,
            metadata = metadata + line + (AiFindingMetadataKeys.DECISION_REASON to "reported by 1 of $n samples"),
        )
    }

    private fun record(
        cluster: List<Member>,
        decision: String,
    ): Record {
        val head = cluster.first().finding
        val ref = head.location as? Location.FileRef
        return Record(
            rule = head.rule,
            file = ref?.path,
            line = ref?.line,
            category = head.metadata[AiFindingMetadataKeys.CATEGORY],
            message = head.message,
            samples = cluster.map { it.sample + 1 },
            similarity = cluster.drop(1).map { it.similarity },
            decision = decision,
        )
    }

    /** The overlap of [a] and [b] when they match (same file, line window, category, similarity), else null. */
    fun similarity(
        a: Finding,
        b: Finding,
        config: AgreementConfig,
    ): Double? {
        val ra = a.location as? Location.FileRef ?: return null
        val rb = b.location as? Location.FileRef ?: return null
        if (ra.path != rb.path) return null
        val la = ra.line
        val lb = rb.line
        if ((la == null) != (lb == null) || (la != null && lb != null && abs(la - lb) > config.lineWindow)) return null
        val ca = a.metadata[AiFindingMetadataKeys.CATEGORY]
        val cb = b.metadata[AiFindingMetadataKeys.CATEGORY]
        if (ca != null && cb != null && !ca.equals(cb, ignoreCase = true)) return null
        return overlap(words(a.message), words(b.message)).takeIf { it >= config.minSimilarity }
    }

    /** |a ∩ b| / min(|a|, |b|); 0 when either is empty. */
    fun overlap(
        a: Set<String>,
        b: Set<String>,
    ): Double = if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / minOf(a.size, b.size)

    fun words(text: String): Set<String> =
        text
            .lowercase()
            .split(WORD_SPLIT)
            .filter { it.length >= MIN_WORD_LENGTH }
            .toSet()

    /** A review comment on a file: the findings samples vote on. */
    private fun votes(finding: Finding): Boolean =
        finding.rule == AiRuleIds.REVIEW_COMMENTS && finding.location is Location.FileRef && !parseError(finding)

    private fun parseError(finding: Finding): Boolean = finding.metadata[WellKnownMetadataKeys.ERROR] == AiRuleAnalyzerUtils.PARSE_ERROR
}
