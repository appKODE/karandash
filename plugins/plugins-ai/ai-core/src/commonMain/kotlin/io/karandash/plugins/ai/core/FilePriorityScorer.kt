package io.karandash.plugins.ai.core

import io.karandash.api.context.EnrichmentContext
import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.rules.FileTier
import io.karandash.plugins.ai.core.rules.TruncationPriorityConfig
import io.karandash.sdk.enrichment.EnrichmentKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Scores each changed file by importance so that [TokenBudget.splitAcrossFiles] can divide the
 * diff budget proportionally and [HunkAwareTruncator] knows which files to keep
 * when truncation is needed.
 *
 * Four weighted signals (configurable via [TruncationPriorityConfig], weights sum
 * to 1.0):
 * - **change volume**: normalised `additions + deletions` across the MR.
 * - **file type**: `HIGH / MEDIUM / LOW` from [FileTier] via
 *   [io.karandash.plugins.ai.core.rules.FileTypeTiers] (reuses `GlobMatcher`).
 * - **hotspot**: commit frequency read from [EnrichmentContext]. Contributes 0.0
 *   when local-git enrichment has not run - graceful degradation.
 * - **path pattern**: reserved for future per-project custom globs; always 0.0
 *   for now so the weight can still be configured without surprises.
 *
 * Final scores are clamped to `[0.0, 1.0]` and the output is sorted descending.
 */
object FilePriorityScorer {
    /** Scored file - preserves the input for downstream consumers. */
    @Serializable
    data class ScoredFile(
        val change: FileChange,
        val score: Double,
        val breakdown: ScoreBreakdown,
    )

    /** Normalised per-signal contribution (each already multiplied by its weight). */
    @Serializable
    data class ScoreBreakdown(
        val changeVolume: Double,
        val fileType: Double,
        val hotspot: Double,
        val pathPattern: Double,
    )

    /**
     * Score every change. Empty input returns an empty list.
     *
     * @param changes Files changed in the MR.
     * @param config Weight configuration (must have already passed its own
     *     `require(weights sum == 1.0)` invariant).
     * @param enrichment Optional enrichment context. Missing or malformed data
     *     contributes 0.0 to the hotspot signal - never throws.
     */
    fun score(
        changes: List<FileChange>,
        config: TruncationPriorityConfig,
        enrichment: EnrichmentContext? = null,
    ): List<ScoredFile> {
        if (changes.isEmpty()) return emptyList()

        val maxVolume = changes.maxOf { it.additions + it.deletions }.coerceAtLeast(1)
        val frequencies = extractFrequencies(enrichment)
        val maxFrequency = frequencies.values.maxOrNull()?.coerceAtLeast(1) ?: 1

        return changes
            .map { change -> scoreOne(change, config, maxVolume, frequencies, maxFrequency) }
            .sortedByDescending { it.score }
    }

    private fun scoreOne(
        change: FileChange,
        config: TruncationPriorityConfig,
        maxVolume: Int,
        frequencies: Map<String, Int>,
        maxFrequency: Int,
    ): ScoredFile {
        val volumeRaw = (change.additions + change.deletions).toDouble() / maxVolume
        val tierRaw = config.fileTypeTiers.tierFor(change.path).score
        val hotspotRaw = if (frequencies.isEmpty()) {
            0.0
        } else {
            (frequencies[change.path] ?: 0).toDouble() / maxFrequency
        }
        val pathPatternRaw = 0.0 // Reserved for future custom patterns (see KDoc).

        val volume = volumeRaw * config.changeVolumeWeight
        val fileType = tierRaw * config.fileTypeWeight
        val hotspot = hotspotRaw * config.hotspotWeight
        val pathPattern = pathPatternRaw * config.pathPatternWeight
        val total = (volume + fileType + hotspot + pathPattern).coerceIn(0.0, 1.0)

        return ScoredFile(
            change = change,
            score = total,
            breakdown = ScoreBreakdown(volume, fileType, hotspot, pathPattern),
        )
    }

    /**
     * Read `{ "frequencies": { "path": count } }` from [EnrichmentContext] if
     * present. Returns empty on missing key, bad shape, or non-integer values.
     */
    private fun extractFrequencies(enrichment: EnrichmentContext?): Map<String, Int> {
        if (enrichment == null) return emptyMap()
        val element = enrichment.get(EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY) ?: return emptyMap()
        return try {
            val root = element as? JsonObject ?: element.jsonObject
            val inner = root["frequencies"] as? JsonObject ?: return emptyMap()
            inner.mapValues { (_, v) -> v.jsonPrimitive.int }
        } catch (_: IllegalArgumentException) {
            emptyMap()
        } catch (_: ClassCastException) {
            emptyMap()
        }
    }
}
