package io.karandash.plugins.ai.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Language-specific prompt additions for AI review comments.
 *
 * These are appended to the base review prompt when the primary language is detected
 * by [LanguageDetector], providing language-idiomatic checks that generic prompts miss.
 *
 * @param customOverlays Additional or overriding language-to-prompt mappings applied on top of
 *   [DEFAULT_OVERLAYS]. Entries here take precedence when there is a key collision.
 */
class LanguagePromptOverlays(
    private val customOverlays: Map<String, String> = emptyMap(),
) {
    private val overlays: Map<String, String> = DEFAULT_OVERLAYS + customOverlays

    /**
     * Returns the language-specific prompt overlay for the given language, or null if none exists.
     *
     * @param language Lowercase language name as returned by [LanguageDetector.detect]
     */
    fun get(language: String?): String? = overlays[language?.lowercase()]

    companion object {
        /** Default language-to-prompt overlays used when no custom overlays are provided. */
        val DEFAULT_OVERLAYS: Map<String, String> = mapOf(
            "kotlin" to """
                Kotlin-specific checks:
                - Check for nullable type misuse and unnecessary !! operators
                - Look for coroutine scope leaks (GlobalScope, missing structured concurrency)
                - Flag use of runBlocking inside coroutines
                - Check for inefficient collection operations (filter+first vs firstOrNull)
                - Verify data class components are not mutable when not needed
            """.trimIndent(),
            "python" to """
                Python-specific checks:
                - Check for mutable default arguments (def foo(x=[]) anti-pattern)
                - Look for missing type annotations on public functions
                - Flag bare except clauses
                - Check for improper use of global/nonlocal
                - Verify f-strings over % or .format() where applicable
            """.trimIndent(),
            "go" to """
                Go-specific checks:
                - Check for goroutine leaks (goroutines started without cancel context)
                - Look for unchecked errors (ignored return values)
                - Flag missing defer for resource cleanup
                - Check for nil pointer dereferences
                - Verify proper use of context.Context propagation
            """.trimIndent(),
            "typescript" to """
                TypeScript-specific checks:
                - Check for improper 'any' usage and suggest specific types
                - Look for missing null/undefined checks
                - Flag non-null assertions (!) when safer alternatives exist
                - Check for Promise handling (missing await, unhandled rejections)
                - Verify proper use of generics vs 'any'
            """.trimIndent(),
            "java" to """
                Java-specific checks:
                - Check for NullPointerException risks (missing null checks, unboxing)
                - Look for resource leaks (streams/connections not closed)
                - Flag synchronized on non-final fields
                - Check for improper equals/hashCode implementations
                - Verify proper exception handling (swallowed exceptions)
            """.trimIndent(),
            "rust" to """
                Rust-specific checks:
                - Check for unnecessary clone() calls
                - Look for unwrap()/expect() that should be propagated with ?
                - Flag blocking operations in async contexts
                - Check for missing lifetime annotations
                - Verify proper use of Result<T,E> vs panic
            """.trimIndent(),
        )

        /**
         * Returns the language-specific prompt overlay using default overlays.
         *
         * @param language Lowercase language name as returned by [LanguageDetector.detect]
         */
        fun get(language: String?): String? = LanguagePromptOverlays().get(language)

        private const val ENGLISH = "en"

        private val OUTPUT_LANGUAGE_NAMES = mapOf(
            "ru" to "Russian",
            "pl" to "Polish",
            "de" to "German",
            "fr" to "French",
            "es" to "Spanish",
            "it" to "Italian",
            "pt" to "Portuguese",
            "uk" to "Ukrainian",
            "nl" to "Dutch",
            "cs" to "Czech",
            "tr" to "Turkish",
            "ja" to "Japanese",
            "zh" to "Chinese",
            "ko" to "Korean",
        )

        /**
         * The T0 output-language instruction for [language] (a BCP-47 tag such as `ru` or `pl-PL`, or an
         * English language name), or `null` for English, which needs no instruction.
         */
        fun outputLanguageInstruction(language: String): String? {
            val raw = language.trim()
            val base = raw.lowercase().substringBefore('-').substringBefore('_')
            val tag = OUTPUT_LANGUAGE_NAMES.entries.firstOrNull { it.value.lowercase() == base }?.key ?: base
            if (tag == ENGLISH || tag == "english" || tag.isEmpty()) return null
            val name = OUTPUT_LANGUAGE_NAMES[tag]?.let { "$it ($tag)" } ?: raw
            return "Output language: reason in English, but write every finding message and the summary in $name. " +
                "Keep code, identifiers, file paths and suggestion blocks verbatim. " +
                "The natural language of the MR content (code comments, docs, changelog, commit messages) " +
                "is never a finding."
        }

        /** [systemPrompt] followed by the [outputLanguageInstruction] of [language]; unchanged for English or null. */
        fun withOutputLanguage(
            systemPrompt: String,
            language: String?,
        ): String = language?.let(::outputLanguageInstruction)?.let { "$systemPrompt\n\n$it" } ?: systemPrompt

        /** `ai.responseLanguage` of a whole config or of a bare ai block; null when unset. */
        fun responseLanguageOf(config: JsonElement?): String? {
            val root = config as? JsonObject ?: return null
            val ai = root["ai"] as? JsonObject ?: root
            return (ai["responseLanguage"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        }
    }
}
