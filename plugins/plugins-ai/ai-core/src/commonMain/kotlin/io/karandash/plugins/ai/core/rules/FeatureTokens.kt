package io.karandash.plugins.ai.core.rules

import io.karandash.sdk.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Response-token cap, its default (a different value counts as user-set) and the temperature of one analyzer. */
@Serializable
data class AnalyzerTokens(
    val maxTokens: Int,
    val defaultMaxTokens: Int,
    val temperature: Double,
)

/**
 * The response-token cap and temperature for the analyzer whose `featureKey` is [featureKey].
 *
 * Analyzers without a limits group of their own read `ai.limits.<featureKey>MaxTokens`; while that key is absent they
 * fall back to the group they used to borrow (`descriptionMaxTokens` or `reviewMaxTokens`), which then warns once at
 * initialization. An unknown feature key gets the review group.
 */
fun AiRuleConfig.tokensFor(featureKey: String): AnalyzerTokens {
    val own = OWN_GROUP[featureKey]
    if (own != null) return own(this)
    val borrowed = BORROWED[featureKey] ?: BorrowedTokens(BorrowedGroup.REVIEW, BorrowedGroup.REVIEW)
    val group = borrowed.maxTokens.of(this)
    return AnalyzerTokens(
        maxTokens = featureMaxTokens[featureKey] ?: group.maxTokens,
        defaultMaxTokens = group.defaultMaxTokens,
        temperature = borrowed.temperature.of(this).temperature,
    )
}

/**
 * Reads `ai.limits.<featureKey>MaxTokens` for the analyzers that used to borrow another group's limits, and warns once
 * through [logger] for each borrowed key that is set while a borrowing analyzer has no key of its own.
 */
internal fun parseFeatureMaxTokens(
    limits: JsonObject,
    logger: Logger?,
): Map<String, Int> {
    val own = BORROWED.keys.mapNotNull { key -> limits[maxTokensKey(key)]?.jsonPrimitive?.intOrNull?.let { key to it } }.toMap()
    BORROWED.entries
        .filter { (key, borrowed) -> key !in own && borrowed.maxTokens.key in limits }
        .groupBy({ it.value.maxTokens.key }, { it.key })
        .forEach { (borrowedKey, features) ->
            logger?.warn(
                "ai.limits.$borrowedKey also caps ${features.joinToString()}; set " +
                    features.joinToString { "ai.limits.${maxTokensKey(it)}" } + " to cap them on their own",
            )
        }
    return own
}

private fun maxTokensKey(featureKey: String): String = "${featureKey}MaxTokens"

private enum class BorrowedGroup(
    val key: String,
    val of: (AiRuleConfig) -> AnalyzerTokens,
) {
    DESCRIPTION(
        "descriptionMaxTokens",
        { AnalyzerTokens(it.descriptionMaxTokens, DescriptionLimits.DEFAULT_MAX_TOKENS, it.descriptionTemperature) },
    ),
    REVIEW(
        "reviewMaxTokens",
        { AnalyzerTokens(it.reviewMaxTokens, ReviewLimits.DEFAULT_MAX_TOKENS, it.reviewTemperature) },
    ),
}

private data class BorrowedTokens(
    val maxTokens: BorrowedGroup,
    val temperature: BorrowedGroup,
)

private val BORROWED: Map<String, BorrowedTokens> =
    mapOf(
        "reviewThreadAnalysis" to BorrowedTokens(BorrowedGroup.DESCRIPTION, BorrowedGroup.DESCRIPTION),
        "designDocDrift" to BorrowedTokens(BorrowedGroup.REVIEW, BorrowedGroup.REVIEW),
        "generateDescription" to BorrowedTokens(BorrowedGroup.REVIEW, BorrowedGroup.DESCRIPTION),
        "mrSummary" to BorrowedTokens(BorrowedGroup.REVIEW, BorrowedGroup.DESCRIPTION),
        "prSplit" to BorrowedTokens(BorrowedGroup.REVIEW, BorrowedGroup.REVIEW),
        "outdatedDocs" to BorrowedTokens(BorrowedGroup.REVIEW, BorrowedGroup.REVIEW),
    )

private val OWN_GROUP: Map<String, (AiRuleConfig) -> AnalyzerTokens> =
    mapOf(
        "changeCategorization" to {
            AnalyzerTokens(it.categorizerMaxTokens, CategorizerLimits.DEFAULT_MAX_TOKENS, it.categorizerTemperature)
        },
        "descriptionQuality" to BorrowedGroup.DESCRIPTION.of,
        "reviewComments" to BorrowedGroup.REVIEW.of,
        "codeQuality" to {
            AnalyzerTokens(it.codeQualityMaxTokens, CodeQualityLimits.DEFAULT_MAX_TOKENS, it.codeQualityTemperature)
        },
        "codeSuggestions" to {
            AnalyzerTokens(it.codeSuggestionMaxTokens, CodeSuggestionLimits.DEFAULT_MAX_TOKENS, it.codeSuggestionTemperature)
        },
        "fixSuggestions" to {
            AnalyzerTokens(it.fixSuggesterMaxTokens, FixSuggestionLimits.DEFAULT_MAX_TOKENS, it.fixSuggesterTemperature)
        },
        "ownershipAnalysis" to {
            AnalyzerTokens(it.ownershipMaxTokens, OwnershipLimits.DEFAULT_MAX_TOKENS, it.ownershipTemperature)
        },
        "similarIssues" to {
            AnalyzerTokens(it.similarIssueMaxTokens, SimilarIssueLimits.DEFAULT_MAX_TOKENS, it.similarIssueTemperature)
        },
        "riskScoring" to {
            AnalyzerTokens(it.riskScorerMaxTokens, RiskScoringLimits.DEFAULT_MAX_TOKENS, it.riskScorerTemperature)
        },
    )
