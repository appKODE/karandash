package io.karandash.config.merge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Merge mode for array handling during config merge.
 */
enum class MergeMode {
    /** Default: child replaces parent. */
    REPLACE,

    /** Child array appended to parent array. */
    APPEND,

    /** Child array prepended to parent array. */
    PREPEND,

    /** Smart merge based on item identity (distinct). */
    MERGE,

    /**
     * Merge arrays of objects by a key field.
     *
     * Items are matched by their `name` field, or their `when` field value (for policies) when they have no name.
     * Child items with a matching key override the parent item.
     * Unmatched parent items are preserved. New child items are appended.
     */
    MERGE_BY_KEY;

    companion object {
        fun fromString(value: String): MergeMode? =
            entries.firstOrNull {
                it.name.equals(value.replace("-", "_"), ignoreCase = true)
            }
    }
}

/** Path-based merge mode map: field dot-path → merge mode for that array. */
typealias MergeStrategyMap = Map<String, MergeMode>

/**
 * Deep-merges [JsonElement] trees with [MergeMode]-based array handling: the one merge implementation behind
 * [JsonElementMerger].
 *
 * Rules:
 * - [JsonObject] + [JsonObject]: recursively merged (child keys override parent, missing keys preserved).
 * - [JsonArray] + [JsonArray]: the per-path [MergeMode] when the strategy names the path; otherwise merged by the
 *   [MatchKeyResolver] key when every item of both arrays carries it, else replaced.
 * - Scalars: override wins.
 * - [JsonNull]: an explicit null clears the parent value: stored as null, or the key removed with `nullRemovesKey`.
 */
object MergeModeMerger {
    private const val NAME_KEY = "name"
    private const val WHEN_KEY = "when"

    /**
     * Deep-merge [override] onto [base].
     *
     * @param base The base element (lower priority, e.g. ancestor config).
     * @param override The override element (higher priority, e.g. child config).
     * @param strategy Optional merge strategy for specific field paths.
     * @param matchKeyResolver Key field for arrays no [strategy] path names; null replaces those arrays. Also picks
     *   the key field of [MergeMode.MERGE_BY_KEY] arrays (default `name`, `when` as fallback).
     * @param nullRemovesKey An explicit null in [override] removes the key instead of storing [JsonNull].
     * @return Merged [JsonElement].
     */
    fun merge(
        base: JsonElement,
        override: JsonElement,
        strategy: MergeStrategyMap? = null,
        matchKeyResolver: MatchKeyResolver? = null,
        nullRemovesKey: Boolean = false,
    ): JsonElement = Pass(strategy, matchKeyResolver, nullRemovesKey).values(base, override, "")

    private class Pass(
        val strategy: MergeStrategyMap?,
        val matchKeyResolver: MatchKeyResolver?,
        val nullRemovesKey: Boolean,
    ) {
        fun values(
            base: JsonElement,
            override: JsonElement,
            path: String,
        ): JsonElement =
            when {
                override is JsonNull -> JsonNull
                base is JsonObject && override is JsonObject -> objects(base, override, path)
                base is JsonArray && override is JsonArray -> arrays(base, override, path)
                else -> override
            }

        private fun objects(
            base: JsonObject,
            override: JsonObject,
            path: String,
        ): JsonObject {
            val result = base.toMutableMap()
            for ((key, overrideValue) in override) {
                val baseValue = result[key]
                when {
                    overrideValue is JsonNull && nullRemovesKey -> result.remove(key)
                    baseValue != null -> result[key] = values(baseValue, overrideValue, if (path.isEmpty()) key else "$path.$key")
                    else -> result[key] = overrideValue
                }
            }
            return JsonObject(result)
        }

        private fun arrays(
            base: JsonArray,
            override: JsonArray,
            path: String,
        ): JsonArray =
            when (strategy?.get(path)) {
                null -> {
                    keyedOrReplaced(base, override, path)
                }

                MergeMode.APPEND -> {
                    JsonArray(base + override)
                }

                MergeMode.PREPEND -> {
                    JsonArray(override + base)
                }

                MergeMode.MERGE -> {
                    JsonArray((base + override).distinct())
                }

                MergeMode.REPLACE -> {
                    override
                }

                MergeMode.MERGE_BY_KEY -> {
                    val field = matchKeyResolver?.resolveMatchKey(path) ?: NAME_KEY
                    byKey(base, override, path) { it.keyValue(field) ?: it.keyValue(WHEN_KEY) }
                }
            }

        private fun keyedOrReplaced(
            base: JsonArray,
            override: JsonArray,
            path: String,
        ): JsonArray {
            val field = matchKeyResolver?.resolveMatchKey(path) ?: return override
            val allKeyed = base.all { it.keyValue(field) != null } && override.all { it.keyValue(field) != null }
            return if (allKeyed) byKey(base, override, path) { it.keyValue(field) } else override
        }

        /**
         * Matched items (same key) deep-merge in base order, unmatched base items stay, new keyed override items
         * append in their order, then the unkeyed override items.
         */
        private fun byKey(
            base: JsonArray,
            override: JsonArray,
            path: String,
            keyOf: (JsonElement) -> String?,
        ): JsonArray {
            val overrideByKey = linkedMapOf<String, JsonElement>()
            val unkeyedOverrides = mutableListOf<JsonElement>()
            for (item in override) {
                val key = keyOf(item)
                if (key != null) overrideByKey[key] = item else unkeyedOverrides.add(item)
            }
            val usedKeys = mutableSetOf<String>()
            val result = base.map { item ->
                val match = keyOf(item)?.takeIf { it in overrideByKey }
                if (match == null) {
                    item
                } else {
                    usedKeys.add(match)
                    values(item, overrideByKey.getValue(match), path)
                }
            }
            return JsonArray(result + overrideByKey.filterKeys { it !in usedKeys }.values + unkeyedOverrides)
        }
    }

    private fun JsonElement.keyValue(field: String): String? = ((this as? JsonObject)?.get(field) as? JsonPrimitive)?.contentOrNull
}
