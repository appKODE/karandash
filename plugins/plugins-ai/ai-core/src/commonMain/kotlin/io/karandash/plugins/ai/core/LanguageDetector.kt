package io.karandash.plugins.ai.core

import io.karandash.models.FileChange

/**
 * Detects the primary programming language of a merge request based on changed file extensions.
 *
 * Uses file extension frequency to determine the dominant language. Extensions are mapped
 * to canonical language names for consistent prompt overlay resolution.
 *
 * @param customMappings Additional or overriding extension-to-language mappings applied on top of
 *   [DEFAULT_EXTENSIONS]. Entries here take precedence when there is a key collision.
 */
class LanguageDetector(
    private val customMappings: Map<String, String> = emptyMap(),
) {
    private val extensionToLanguage: Map<String, String> = DEFAULT_EXTENSIONS + customMappings

    /**
     * Detects the primary language from MR file changes by extension frequency.
     *
     * @param changes List of file changes from the merge request
     * @return The detected language name (lowercase), or null if no recognized extensions
     */
    fun detect(changes: List<FileChange>): String? {
        val counts = mutableMapOf<String, Int>()
        for (change in changes) {
            val ext = change.path.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) continue
            val lang = extensionToLanguage[ext] ?: continue
            counts[lang] = (counts[lang] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key
    }

    companion object {
        /** Default extension-to-language mappings used when no custom mappings are provided. */
        val DEFAULT_EXTENSIONS: Map<String, String> = mapOf(
            "kt" to "kotlin",
            "kts" to "kotlin",
            "java" to "java",
            "py" to "python",
            "go" to "go",
            "ts" to "typescript",
            "tsx" to "typescript",
            "js" to "javascript",
            "jsx" to "javascript",
            "rs" to "rust",
            "swift" to "swift",
            "rb" to "ruby",
            "cs" to "csharp",
            "cpp" to "cpp",
            "cc" to "cpp",
            "cxx" to "cpp",
            "c" to "c",
            "scala" to "scala",
            "ex" to "elixir",
            "exs" to "elixir",
            "hs" to "haskell",
            "php" to "php",
        )

        /**
         * Detects the primary language using default extension mappings.
         *
         * @param changes List of file changes from the merge request
         * @return The detected language name (lowercase), or null if no recognized extensions
         */
        fun detect(changes: List<FileChange>): String? = LanguageDetector().detect(changes)
    }
}
