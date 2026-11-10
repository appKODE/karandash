@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.dispatch

import io.karandash.api.run.dumpJson
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.pipeline.AiMetadataTransformer
import io.karandash.plugins.ai.core.pipeline.FindingCap
import io.karandash.plugins.ai.core.pipeline.FindingDuplicates
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * review-comments `maxComments` caps the comments of the whole MR, not of each diff chunk.
 *
 * Repeats collapse first ([FindingDuplicates.inRun] with `ai.dedup`), then [FindingCap] cuts by score (`ai.ranking`):
 * a BUG/SEC/DATA/LEAK comment below the cut becomes INFO, any other is dropped. A code suggestion follows the comment
 * it pairs with ([AiMetadataTransformer.pairsWith]: same path and line, whatever the end line); an unpaired one is
 * dropped when the cap cuts. An optional `maxCommentsHard` caps every category. Decisions are dumped as
 * `ai/stages/dedup.json` and `ai/stages/ranking.json`.
 */
internal object ReviewCommentsCap {
    suspend fun apply(
        ruleId: String,
        config: JsonElement,
        findings: List<Finding>,
    ): List<Finding> {
        if (ruleId != AiRuleIds.REVIEW_COMMENTS) return findings
        val limits = AiRuleConfig.fromPluginConfig(config).review
        val aiConfig = AiPluginConfig.fromPluginConfig(config)
        val max = ((config as? JsonObject)?.get("maxComments") as? JsonPrimitive)?.intOrNull ?: limits.maxComments
        val hard = limits.maxCommentsHard
        val dedup = FindingDuplicates.inRun(findings, aiConfig.dedup)
        dumpJson("ai/stages/dedup.json") { dedup.records }
        val unique = dedup.findings
        val comments = unique.filter { it.rule == AiRuleIds.REVIEW_COMMENTS }
        if (comments.size <= minOf(max, hard ?: max)) return unique
        val paired = unique.filter { f ->
            f.rule == AiRuleIds.REVIEW_COMMENTS ||
                (
                    f.rule == AiRuleIds.CODE_SUGGESTIONS &&
                        comments.any { it.location != null && AiMetadataTransformer.pairsWith(f, it) }
                )
        }
        val capped = FindingCap.keep(paired, max, hard, aiConfig.ranking)
        dumpJson("ai/stages/ranking.json") { capped.records }
        return capped.findings + unique.filter { it.rule != AiRuleIds.REVIEW_COMMENTS && it.rule != AiRuleIds.CODE_SUGGESTIONS }
    }
}
