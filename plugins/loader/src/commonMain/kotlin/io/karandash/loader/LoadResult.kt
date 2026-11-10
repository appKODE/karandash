package io.karandash.loader

import io.karandash.api.errors.KarandashError
import kotlinx.serialization.Serializable

/**
 * Result of loading plugins from directories.
 *
 * @property loaded List of successfully loaded module IDs
 * @property failed List of failed load attempts with path and reason
 * @property skipped List of filenames that were skipped
 * @property sources The plugin file each loaded module id came from, where the loader knows it
 */
@Serializable
data class LoadResult(
    val loaded: List<String>,
    val failed: List<LoadFailure>,
    val skipped: List<String>,
    val sources: Map<String, String> = emptyMap(),
) {
    /**
     * Represents a single failed plugin load attempt.
     *
     * @property path The file path or module ID that failed to load
     * @property reason Human-readable error message describing the failure
     */
    @Serializable
    data class LoadFailure(
        val path: String,
        val reason: String
    ) {
        /** the user-facing line, carrying [KarandashError.PLUGIN_LOAD_FAILED]'s code. */
        val message: String
            get() = KarandashError.PLUGIN_LOAD_FAILED.let { error ->
                "${error.bracketCode} ${error.format(mapOf("plugin" to path, "error" to reason))}"
            }
    }

    companion object {
        /**
         * Merge multiple [LoadResult] instances into a single result.
         *
         * @param results The results to merge
         * @return A single [LoadResult] with all loaded, failed, and skipped entries combined
         */
        fun merge(results: List<LoadResult>): LoadResult =
            LoadResult(
                loaded = results.flatMap { it.loaded },
                failed = results.flatMap { it.failed },
                skipped = results.flatMap { it.skipped },
                sources = results.fold(emptyMap()) { acc, result -> acc + result.sources },
            )
    }
}
