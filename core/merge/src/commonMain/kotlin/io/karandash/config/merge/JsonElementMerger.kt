package io.karandash.config.merge

import io.karandash.config.MergeStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The one config merger, both entry points running [MergeModeMerger]:
 *
 * - [mergeLayers] merges whole config layers (extends chain, profiles, CLI layers): arrays replace unless a
 *   `merge-strategy` path says otherwise, `policies` merge by key, and an explicit null is kept (a module key set to
 *   null means "enabled with defaults").
 * - [merge] applies an override onto one rule's config (`when.override`, policy `config`): lists of objects merge
 *   by the [MatchKeyResolver] key (default `name`) and an explicit null removes the key, so the rule falls back to
 *   its default instead of reading a JSON null.
 */
object JsonElementMerger {
    /** Merge modes every config layer merge applies unless its own strategy names the path. */
    val BUILT_IN_STRATEGIES: MergeStrategyMap = mapOf(
        "policies" to MergeMode.MERGE_BY_KEY,
    )

    /**
     * Deep-merge the override [override] onto the rule config [base].
     *
     * @param matchKeyResolver Resolves the match key for a given dot-separated field path.
     *   Returns `null` to use plain replacement for that list. Defaults to always returning `"name"`.
     */
    fun merge(
        base: JsonElement,
        override: JsonElement,
        matchKeyResolver: MatchKeyResolver = MatchKeyResolver.DEFAULT,
    ): JsonElement = MergeModeMerger.merge(base, override, matchKeyResolver = matchKeyResolver, nullRemovesKey = true)

    /**
     * Merge the config layer [override] onto [base] by [topLevel]: [MergeStrategy.DEEP] deep-merges with
     * [strategy] paths winning over [BUILT_IN_STRATEGIES], [MergeStrategy.SHALLOW] replaces each top-level key
     * whole, and [MergeStrategy.REPLACE] drops [base].
     */
    fun mergeLayers(
        base: JsonElement,
        override: JsonElement,
        strategy: MergeStrategyMap? = null,
        topLevel: MergeStrategy = MergeStrategy.DEEP,
    ): JsonElement =
        when (topLevel) {
            MergeStrategy.DEEP -> MergeModeMerger.merge(base, override, BUILT_IN_STRATEGIES + strategy.orEmpty())
            MergeStrategy.SHALLOW -> if (base is JsonObject && override is JsonObject) JsonObject(base + override) else override
            MergeStrategy.REPLACE -> override
        }
}

/**
 * Resolves the match key used for list-of-object merging at a given field path.
 */
fun interface MatchKeyResolver {
    /**
     * Returns the match key for the given dot-separated [path], or `null` to skip
     * match-key merging and use plain replacement.
     */
    fun resolveMatchKey(path: String): String?

    companion object {
        /** Always returns `"name"` as the match key. */
        val DEFAULT: MatchKeyResolver = MatchKeyResolver { "name" }
    }
}
