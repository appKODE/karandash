package io.karandash.api.ai

import io.karandash.api.context.PrecomputedDiffContext
import io.karandash.models.MergeRequest

/**
 * Middleware that transforms a [PrecomputedDiffContext] before it reaches AI analyzers.
 *
 * Processors are applied in order after the initial context is built, allowing
 * plugins to enrich, filter, or transform the diff/metadata that analyzers see.
 */
public fun interface AiContextProcessor {
    /** Returns a (possibly modified) copy of [context] for downstream consumption. */
    public suspend fun process(
        context: PrecomputedDiffContext,
        mr: MergeRequest
    ): PrecomputedDiffContext
}
