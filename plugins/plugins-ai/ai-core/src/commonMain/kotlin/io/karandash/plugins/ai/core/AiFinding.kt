package io.karandash.plugins.ai.core

import io.karandash.models.finding.Finding

private const val CUSTOM_AI_RULE_PREFIX = "ai-"

/**
 * Whether this finding came from the AI pipeline. Analyzer rule ids carry no prefix (`review-comments`), so the
 * provenance decides: the dispatcher stamps the provider, analyzers stamp the model. Custom AI rules keep their
 * `ai-` id prefix. The one predicate every AI stage (filter, contradictions, correlation, carry-over) uses.
 */
fun Finding.isAiFinding(): Boolean =
    AiFindingMetadataKeys.AI_PROVIDER in metadata ||
        AiFindingMetadataKeys.AI_MODEL in metadata ||
        rule.startsWith(CUSTOM_AI_RULE_PREFIX)
