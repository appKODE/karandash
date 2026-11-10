package io.karandash.plugins.ai.pipeline

import io.karandash.models.support.KarandashJson
import io.karandash.normalize.ConfigKeyNormalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/** `ai.metrics.changeEntropy` sub-options. */
@Serializable
data class ChangeEntropyOptions(
    /** Adds the MR's normalized change entropy to every finding's metadata as `changeEntropy`. */
    val includeInMetadata: Boolean = false,
)

/** `ai.metrics.cognitiveLoad` sub-options. */
@Serializable
data class CognitiveLoadOptions(
    /** Reports the estimated review time for every MR, not only the HIGH-load ones. */
    val estimatedReviewTime: Boolean = false,
)

/** `ai.metrics.hotspotAnalysis` (alias of `ai.metrics.hotspot`) sub-options. */
@Serializable
data class HotspotOptions(
    /** Changed lines in a file at which its churn score reaches 1.0. */
    val churnThreshold: Int = DEFAULT_CHURN_LINES,
    /** Changed lines in a file at which its complexity score reaches 1.0. */
    val complexityThreshold: Int = DEFAULT_COMPLEXITY_LINES,
) {
    companion object {
        const val DEFAULT_CHURN_LINES = 200
        const val DEFAULT_COMPLEXITY_LINES = 100
    }
}

/** `ai.cloneDetection`: enables NCD clone detection with a similarity threshold. */
@Serializable
data class CloneDetectionOptions(
    val enabled: Boolean = true,
    /** Minimum similarity (1 − NCD, 0.0–1.0) for a pair of changed files to be reported. */
    val threshold: Double = DEFAULT_THRESHOLD,
    /** `warn` reports clones as WARNING; anything else (default `info`) as INFO. */
    val onDetection: String = "info",
) {
    companion object {
        const val DEFAULT_THRESHOLD = 0.75
    }
}

/** `ai.naturalness`: enables naturalness scoring. */
@Serializable
data class NaturalnessOptions(
    val enabled: Boolean = true,
    /** `false` logs unusual files instead of reporting them as a finding. */
    val flagUnusualPatterns: Boolean = true,
    /** Flags files whose score `1 − e^(−average surprise)` exceeds this (0.0–1.0); absent = token-count rule. */
    val surpriseThreshold: Double? = null,
)

/** Metric sub-options read from the raw `ai` block; a block that is absent or malformed keeps its default. */
@Serializable
internal data class AiMetricsOptions(
    val changeEntropy: ChangeEntropyOptions = ChangeEntropyOptions(),
    val cognitiveLoad: CognitiveLoadOptions = CognitiveLoadOptions(),
    val hotspot: HotspotOptions = HotspotOptions(),
    val cloneDetection: CloneDetectionOptions? = null,
    val naturalness: NaturalnessOptions? = null,
) {
    companion object {
        /** Reads the options from the `ai` block (or a config whose `ai` key holds it); [warn] reports a malformed block. */
        fun from(
            config: JsonElement?,
            warn: (String) -> Unit,
        ): AiMetricsOptions {
            val obj = config as? JsonObject ?: return AiMetricsOptions()
            val ai = obj["ai"] as? JsonObject ?: obj
            val metrics = ai["metrics"] as? JsonObject ?: JsonObject(emptyMap())
            return AiMetricsOptions(
                changeEntropy = decode(lookup(metrics, "changeEntropy"), "metrics.changeEntropy", warn) ?: ChangeEntropyOptions(),
                cognitiveLoad = decode(lookup(metrics, "cognitiveLoad"), "metrics.cognitiveLoad", warn) ?: CognitiveLoadOptions(),
                hotspot =
                    decode(lookup(metrics, "hotspotAnalysis") ?: lookup(metrics, "hotspot"), "metrics.hotspotAnalysis", warn)
                        ?: HotspotOptions(),
                cloneDetection = decode(block(ai["cloneDetection"]), "cloneDetection", warn),
                naturalness = decode(block(ai["naturalness"]), "naturalness", warn),
            )
        }

        private fun lookup(
            section: JsonObject,
            camelKey: String,
        ): JsonElement? = section[camelKey] ?: section[ConfigKeyNormalizer.camelToKebabCase(camelKey)]

        /** `key: true` / `key: false` is shorthand for `{ enabled: ... }`. */
        internal fun block(value: JsonElement?): JsonElement? =
            (value as? JsonPrimitive)?.booleanOrNull?.let { JsonObject(mapOf("enabled" to JsonPrimitive(it))) } ?: value

        /** Decodes the [value] object as [T]; null when it is not an object or is malformed ([warn] reports why). */
        internal inline fun <reified T> decode(
            value: JsonElement?,
            key: String,
            warn: (String) -> Unit,
        ): T? {
            if (value !is JsonObject) return null
            return try {
                KarandashJson.Default.decodeFromJsonElement<T>(value)
            } catch (e: SerializationException) {
                warn("AI config: ignoring malformed ai.$key: ${e.message}")
                null
            } catch (e: IllegalArgumentException) {
                warn("AI config: ignoring malformed ai.$key: ${e.message}")
                null
            }
        }
    }
}
