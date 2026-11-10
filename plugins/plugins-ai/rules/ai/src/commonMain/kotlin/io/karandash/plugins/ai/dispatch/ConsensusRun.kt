package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiService
import io.karandash.logger.Logger
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.ConsensusAnalyzer
import io.karandash.plugins.ai.providers.ConsensusStrategy
import kotlinx.atomicfu.atomic

/**
 * `ai.consensusModels`: runs a rule once per consensus model and keeps the findings enough of them report
 * (`ai.consensusStrategy`), matched by [ConsensusAnalyzer.findingFingerprint]. A model that finds nothing counts
 * as disagreeing; a model whose call failed abstains (warned), so one rate-limited model does not empty the vote.
 * Models run one after another, so provider rate limits see one call at a time.
 */
internal object ConsensusRun {
    /**
     * Runs [once] with [model] alone when [enabled] is false or fewer than two consensus models are set.
     * [once] routes its model calls through [Probe.watch] so a failure the rule swallows is still seen.
     */
    suspend fun run(
        cfg: AiPluginConfig,
        enabled: Boolean,
        model: String,
        logger: Logger,
        once: suspend (model: String, probe: Probe) -> List<Finding>,
    ): List<Finding> {
        val models = cfg.consensusModels
        if (!enabled || models.size < 2) return once(model, Probe())
        val votes = models.mapNotNull { vote(it, logger, once) }
        val voters = votes.map { it.first }
        if (voters.size < models.size) {
            logger.warn("AI consensus: ${models.size - voters.size} of ${models.size} models failed, voting among ${voters.size}")
        }
        if (voters.isEmpty()) return emptyList()
        val strategy = ConsensusStrategy.fromString(cfg.consensusStrategy)
        val result = ConsensusAnalyzer.intersect(votes.map { it.second }, strategy.minAgreement(voters.size))
        logger.debug(
            "AI consensus: kept ${result.findings.size} of ${result.totalCandidates} findings " +
                "(${voters.joinToString(",")}, strategy=$strategy)",
        )
        return result.findings.map { finding ->
            val agreed = finding.metadata[CONSENSUS_MODELS]
                .orEmpty()
                .split(",")
                .mapNotNull { voters.getOrNull(it.toIntOrNull() ?: -1) }
            val names = agreed.joinToString(",")
            finding.copy(metadata = finding.metadata + mapOf(CONSENSUS_MODELS to names, AiFindingMetadataKeys.AI_MODEL to names))
        }
    }

    /** One model's findings, or null when one of its calls failed. */
    private suspend fun vote(
        model: String,
        logger: Logger,
        once: suspend (model: String, probe: Probe) -> List<Finding>,
    ): Pair<String, List<Finding>>? {
        val probe = Probe()
        val findings = try {
            once(model, probe)
        } catch (e: AiProviderException) {
            logger.warn("AI consensus: model $model failed (${e.message}), it does not vote")
            return null
        }
        if (probe.failed) logger.warn("AI consensus: a call to model $model failed, it does not vote")
        return (model to findings).takeUnless { probe.failed }
    }

    /** Records whether a model call failed, including one the rule caught and turned into "no findings". */
    class Probe {
        private val failure = atomic(false)
        val failed: Boolean get() = failure.value

        fun watch(service: AiService): AiService =
            object : AiService by service {
                override suspend fun complete(
                    request: AiCompletionRequest,
                    providerId: String?,
                ): AiCompletionResponse =
                    try {
                        service.complete(request, providerId)
                    } catch (e: AiProviderException) {
                        failure.value = true
                        throw e
                    }
            }
    }

    /** Metadata key [ConsensusAnalyzer] stamps with the agreeing model indices; replaced here by their names. */
    private const val CONSENSUS_MODELS = "consensusModels"
}
