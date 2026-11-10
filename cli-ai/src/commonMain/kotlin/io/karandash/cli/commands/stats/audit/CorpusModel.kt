package io.karandash.cli.commands.stats.audit

import kotlinx.serialization.Serializable

/**
 * One merge request of the frozen review-history corpus, as written by `tools/audit/fetch_gitlab_corpus.py`
 * to `<corpus>/<project>/<iid>.json`. Keyed by content (rule, path, message, anchor snippet), never by a
 * karandash fingerprint, so fingerprint changes cannot break baseline comparison.
 */
@Serializable
data class CorpusMr(
    val project: String,
    val iid: Int,
    val createdAt: String,
    val mergedAt: String? = null,
    val state: String = "",
    /** sha256 of the MR's sorted `changes` diffs; MRs sharing it are duplicates. */
    val diffHash: String = "",
    val finalHeadSha: String = "",
    val versions: List<CorpusVersion> = emptyList(),
    val discussions: List<CorpusDiscussion> = emptyList(),
)

/** One MR version (push). */
@Serializable
data class CorpusVersion(
    val headSha: String,
    val createdAt: String = "",
)

/** One discussion thread with all its notes in posting order. */
@Serializable
data class CorpusDiscussion(
    val id: String,
    val notes: List<CorpusNote> = emptyList(),
)

/** One note; [anchor] is filled for bot inline notes only. */
@Serializable
data class CorpusNote(
    val id: Long,
    val author: String,
    val isBot: Boolean = false,
    val body: String = "",
    val createdAt: String = "",
    val system: Boolean = false,
    val resolved: Boolean = false,
    val resolvedBy: String? = null,
    val position: CorpusPosition? = null,
    val awards: List<CorpusAward> = emptyList(),
    val anchor: CorpusAnchor? = null,
)

/** Diff position of an inline note. */
@Serializable
data class CorpusPosition(
    val newPath: String? = null,
    val newLine: Int? = null,
    val oldLine: Int? = null,
    val headSha: String? = null,
)

/** One award emoji (`thumbsup`, `thumbsdown`, ...) on a note. */
@Serializable
data class CorpusAward(
    val name: String,
    val user: String,
)

/**
 * File lines around an inline note's anchor: [snippet] at the note's head sha, [finalSnippet] the same line
 * window at the final MR head (`null` when the file is gone there). [startLine] is the 1-based number of the first line.
 */
@Serializable
data class CorpusAnchor(
    val startLine: Int,
    val snippet: List<String> = emptyList(),
    val finalSnippet: List<String>? = null,
)
