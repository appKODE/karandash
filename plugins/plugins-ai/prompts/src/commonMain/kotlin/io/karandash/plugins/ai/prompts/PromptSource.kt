package io.karandash.plugins.ai.prompts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Describes where to load a system prompt from.
 *
 * Resolution priority: [Inline] > [File] > [Url] > [Builtin].
 * [Layered] concatenates multiple sources in order.
 */
sealed class PromptSource {
    /** Use the built-in default prompt for the rule. */
    data object Builtin : PromptSource()

    /** Inline prompt text provided directly in config. */
    @Serializable
    data class Inline(
        val text: String,
    ) : PromptSource()

    /** Prompt loaded from a local file path. */
    @Serializable
    data class File(
        val path: String,
    ) : PromptSource()

    /**
     * Prompt fetched from a remote URL.
     *
     * Supports TOML files (extracts `[section]`), plain text, and Markdown.
     */
    @Serializable
    data class Url(
        val url: String,
        val format: String = "text",
        val section: String? = null,
        val sha256: String? = null,
    ) : PromptSource()

    /**
     * Multiple prompt sources concatenated in order.
     *
     * If [includeBuiltin] is true, the built-in default prompt is prepended first.
     * Sources are separated by a blank line. Nested [Layered] sources are not supported.
     *
     * Not @Serializable: runtime context holder, holds a non-serializable `PromptSource`.
     */
    data class Layered(
        val sources: List<PromptSource>,
        val includeBuiltin: Boolean = false,
    ) : PromptSource()

    /**
     * Separate system and user prompt sources.
     *
     * When the config provides `system:` and/or `user:` sub-keys, the prompt
     * is split into a system prompt and a user prompt resolved independently.
     *
     * Not @Serializable: runtime context holder, holds a non-serializable `PromptSource`.
     */
    data class Pair(
        val system: PromptSource,
        val user: PromptSource,
    ) : PromptSource()

    companion object {
        private const val KEY_SYSTEM = "system"
        private const val KEY_USER = "user"
        private const val KEY_SOURCES = "sources"
        private const val KEY_INCLUDE_BUILTIN = "includeBuiltin"
        private const val KEY_INCLUDE_BUILTIN_KEBAB = "include-builtin"
        private const val KEY_URL = "url"
        private const val KEY_FORMAT = "format"
        private const val KEY_SECTION = "section"
        private const val KEY_SHA256 = "sha256"
        private const val KEY_FILE = "file"
        private const val KEY_INLINE = "inline"
        private const val KEY_LAYERED = "layered"

        /**
         * Parses a prompt source from a [JsonElement].
         *
         * Recognized keys: `system`, `user`, `sources`,
         * `url`, `file`, `inline`. When none are present, returns [Builtin].
         */
        fun fromJson(element: JsonElement): PromptSource {
            // A JsonPrimitive here means ExternalValueResolver already resolved a
            // { file: "..." } reference to its content - treat it as inline text.
            if (element is JsonPrimitive) {
                val text = element.contentOrNull
                return if (!text.isNullOrBlank()) Inline(text) else Builtin
            }
            if (element !is JsonObject) return Builtin
            val obj = element

            if (KEY_SYSTEM in obj || KEY_USER in obj) {
                val systemSource = obj[KEY_SYSTEM]?.let { fromJson(it) } ?: Builtin
                val userSource = obj[KEY_USER]?.let { fromJson(it) } ?: Builtin
                return Pair(system = systemSource, user = userSource)
            }

            val layeredObj = obj[KEY_LAYERED] as? JsonObject
            if (layeredObj != null) {
                val includeBuiltin = (layeredObj[KEY_INCLUDE_BUILTIN] as? JsonPrimitive)?.booleanOrNull
                    ?: (layeredObj[KEY_INCLUDE_BUILTIN_KEBAB] as? JsonPrimitive)?.booleanOrNull
                    ?: false
                val sources = (layeredObj[KEY_SOURCES] as? JsonArray)?.map { fromJson(it) } ?: emptyList()
                return Layered(sources = sources.filter { it !is Layered }, includeBuiltin = includeBuiltin)
            }

            if (KEY_SOURCES in obj) {
                val sources = (obj[KEY_SOURCES] as? JsonArray)?.map { fromJson(it) } ?: emptyList()
                val includeBuiltin = (obj[KEY_INCLUDE_BUILTIN] as? JsonPrimitive)?.booleanOrNull
                    ?: (obj[KEY_INCLUDE_BUILTIN_KEBAB] as? JsonPrimitive)?.booleanOrNull
                    ?: false
                return Layered(sources = sources.filter { it !is Layered }, includeBuiltin = includeBuiltin)
            }

            val urlStr = (obj[KEY_URL] as? JsonPrimitive)?.contentOrNull
            if (urlStr != null) {
                return Url(
                    url = urlStr,
                    format = (obj[KEY_FORMAT] as? JsonPrimitive)?.contentOrNull ?: "text",
                    section = (obj[KEY_SECTION] as? JsonPrimitive)?.contentOrNull,
                    sha256 = (obj[KEY_SHA256] as? JsonPrimitive)?.contentOrNull,
                )
            }

            val filePath = (obj[KEY_FILE] as? JsonPrimitive)?.contentOrNull
            if (filePath != null) return File(path = filePath)

            val inlineText = (obj[KEY_INLINE] as? JsonPrimitive)?.contentOrNull
            if (inlineText != null) return Inline(text = inlineText)

            return Builtin
        }
    }
}
