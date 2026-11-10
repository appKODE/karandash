package io.karandash.api.context

import kotlinx.serialization.Serializable

/**
 * Pre-built diff and metadata context shared across all AI analyzers in a single validation run.
 *
 * Diff sections and metadata are expensive to compute, so they are computed once per MR dispatch
 * and shared by every analyzer.
 *
 * @property metadata Human-readable summary of the MR (title, author, branches, file list).
 * @property preparedDiff Truncated/preprocessed diff section built by `DiffSectionBuilder.build`.
 * @property fullDiff Raw concatenation of every file's diff content (untruncated).
 * @property omittedFileCount Number of files whose diff was present but excluded from [preparedDiff] due to size limits.
 * @property fullDiffCharCount Total character count of all raw diff content across all changed files.
 */
@Serializable
public data class PrecomputedDiffContext(
    val metadata: String = "",
    val preparedDiff: String = "",
    val fullDiff: String = "",
    val omittedFileCount: Int = 0,
    val fullDiffCharCount: Int = 0,
)
