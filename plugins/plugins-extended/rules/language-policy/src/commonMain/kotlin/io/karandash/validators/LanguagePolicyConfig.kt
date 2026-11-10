package io.karandash.validators

import io.karandash.api.RuleConfig
import kotlinx.serialization.Serializable

@Serializable
data class LanguagePolicyConfig(
    val checkMrTitle: Boolean = true,
    val checkMrDescription: Boolean = true,
    val checkCommitTitle: Boolean = true,
    val checkCommitDescription: Boolean = true,
    val checkBranchName: Boolean = true,
    val allowedScripts: List<String> = DEFAULT_ALLOWED_SCRIPTS,
    val additionalAllowedRanges: List<String> = emptyList(),
    val languageName: String = DEFAULT_LANGUAGE_NAME,
) : RuleConfig {
    /**
     * Script names from [allowedScripts] that are not in [KNOWN_SCRIPTS].
     * Checked at validation time to emit a warning finding (no logger access here).
     */
    val unknownScripts: List<String> = allowedScripts.filter { it.lowercase() !in KNOWN_SCRIPTS }

    companion object {
        private const val DEFAULT_SCRIPT = "latin"
        private val DEFAULT_ALLOWED_SCRIPTS = listOf(DEFAULT_SCRIPT)
        private const val DEFAULT_LANGUAGE_NAME = "English"
        private val KNOWN_SCRIPTS = setOf(
            "latin",
            "cyrillic",
            "greek",
            "arabic",
            "cjk",
            "hebrew",
            "devanagari",
        )

        /** Returns the set of recognized script names for diagnostic messages. */
        fun knownScriptNames(): Set<String> = KNOWN_SCRIPTS
    }
}
