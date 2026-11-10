package io.karandash.plugins.ai.core.pipeline

import kotlinx.serialization.Serializable

/**
 * The second-opinion verifier's name for [AiGroundingVerifier]: grounding and the verifier pass are one
 * class; `FindingVerifier(aiService, model, budget, permits)` builds it and [AiGroundingVerifier.secondOpinion] runs
 * the pass.
 */
typealias FindingVerifier = AiGroundingVerifier

@Serializable
internal data class VerifierReply(
    val verdicts: List<VerifierVerdict> = emptyList(),
)

@Serializable
internal data class VerifierVerdict(
    val index: Int,
    val verdict: String,
    val reason: String? = null,
)
