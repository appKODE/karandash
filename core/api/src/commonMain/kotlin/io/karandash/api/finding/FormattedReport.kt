package io.karandash.api.finding

import kotlinx.serialization.Serializable

/**
 * Bridge data class carrying formatted output from a [FormatModule][io.karandash.api.module.FormatModule]
 * to an [OutputModule][io.karandash.api.module.OutputModule].
 *
 * @property formatId Identifier of the format that produced this report (e.g. "markdown", "json").
 * @property body The main formatted content (markdown text, JSON string, XML, etc.).
 * @property inlineComments File-level comments for platform inline posting. Empty for non-platform outputs.
 * @property commentMarker Marker string for summary comment deduplication on platforms.
 * @property inlineMarker Marker string for inline comment deduplication on platforms.
 * @property metadata Format-specific metadata (e.g. SARIF schema version, report title).
 * @property changedFiles File paths that were re-reviewed in this run (from incremental cache).
 *   null = no cache context (resolve all inline comments, post all).
 *   empty = full cache hit (skip inline resolution and posting).
 *   non-empty = partial cache hit (only resolve/post for these files).
 */
@Serializable
public data class FormattedReport(
    val formatId: String,
    val body: String,
    val inlineComments: List<InlineCommentData> = emptyList(),
    val commentMarker: String? = null,
    val inlineMarker: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val changedFiles: Set<String>? = null,
)

/**
 * A single inline comment targeting a specific file and line in the MR diff.
 *
 * @property filePath File path relative to the repository root.
 * @property line The line number in the new version of the file (end of range for multi-line).
 * @property startLine Optional start line for multi-line comment ranges.
 * @property body The markdown body of the inline comment. A code suggestion inside it is wrapped in
 *   [SUGGESTION_START] / [SUGGESTION_END] around a default ```` ```diff ```` block, so a platform output
 *   can re-render it with the platform's native suggestion syntax.
 */
@Serializable
public data class InlineCommentData(
    val filePath: String,
    val line: Int,
    val startLine: Int? = null,
    val body: String,
) {
    public companion object {
        /** Opens a code-suggestion region in [InlineCommentData.body]. */
        public const val SUGGESTION_START: String = "<!-- karandash-suggestion -->"

        /** Closes a code-suggestion region in [InlineCommentData.body]. */
        public const val SUGGESTION_END: String = "<!-- /karandash-suggestion -->"
    }
}
