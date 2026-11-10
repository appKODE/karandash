package io.karandash.validators

import io.karandash.api.RuleConfig
import io.karandash.sdk.config.BlockMode
import io.karandash.sdk.config.BlockModeFalseOffSerializer
import kotlinx.serialization.Serializable

@Serializable
data class DraftConfig(
    /** `fail | warn | off`; `true` = fail, `false` = off. */
    @Serializable(with = BlockModeFalseOffSerializer::class)
    val blockDraft: BlockMode = BlockMode.FAIL,
    val wipPatterns: List<String> = DEFAULT_WIP_PATTERNS,
    // Structured config - alternative to `wipPatterns`. Ignored when `wipPatterns` is set explicitly.
    val wipMarkers: List<String>? = null,
) : RuleConfig {
    companion object {
        val DEFAULT_WIP_PATTERNS = listOf("\\bWIP\\b", "\\[WIP\\]", "\\bwip:", "\\bWIP:")

        /**
         * Builds regex patterns from plain [wipMarkers] strings by escaping each
         * marker and wrapping it in a word-boundary assertion where appropriate.
         */
        fun buildPatternsFromMarkers(markers: List<String>): List<String> =
            markers.map { marker ->
                val escaped = Regex.escape(marker)
                // Wrap in word boundaries only when the marker is purely word-character based
                if (marker.all { it.isLetterOrDigit() || it == '_' }) "\\b$escaped\\b" else escaped
            }
    }

    /**
     * Returns the effective WIP patterns: explicit [wipPatterns] unless they equal the default
     * AND [wipMarkers] is set, in which case markers take precedence.
     */
    fun effectiveWipPatterns(): List<String> =
        if (wipMarkers != null && wipPatterns == DEFAULT_WIP_PATTERNS) {
            buildPatternsFromMarkers(wipMarkers)
        } else {
            wipPatterns
        }
}
