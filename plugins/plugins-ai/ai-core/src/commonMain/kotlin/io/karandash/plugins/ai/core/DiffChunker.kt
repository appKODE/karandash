package io.karandash.plugins.ai.core

import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import kotlinx.serialization.Serializable

/**
 * Splits large MR diffs into manageable chunks for multi-pass AI analysis.
 *
 * When a diff exceeds `maxCharsPerChunk`, files are grouped into chunks that fit
 * within the budget. Each chunk is a subset of the MR's file changes that can be
 * analyzed independently.
 *
 * Chunking strategy:
 * 1. Group files into [ReviewUnits], tests next to their sources, the riskiest unit first; within a unit, sort
 *    by priority: security configs > source code > test files > generated/vendor
 * 2. Greedily pack files into chunks respecting the character budget
 * 3. A file larger than `maxCharsPerChunk` is split on `@@` hunk boundaries into windows of at most
 *    `maxCharsPerChunk` characters, each repeating up to [overlapChars] characters of the previous
 *    window's trailing hunks, or as many trailing hunks as fit in [overlapLines] lines when that carries more
 *    (`ai.limits.chunkOverlapLines`; a single hunk larger than the budget stays whole)
 * 4. At most `maxChunks` chunks are built; files and hunk windows past the limit are in none, and [split]
 *    reports them as [DroppedDiff] for the caller's ledger
 */
class DiffChunker(
    private val maxCharsPerChunk: Int = AiRuleConfig.DEFAULT_MAX_DIFF_CHARS,
    private val maxChunks: Int = AiRuleConfig.DEFAULT_MAX_CHUNKS,
    private val overlapChars: Int = 0,
    private val overlapLines: Int = 0,
) {
    /**
     * Splits the MR's changes into chunks.
     *
     * @param mr The merge request to chunk
     * @return List of [DiffChunk], each containing a subset of file changes
     */
    fun chunk(mr: MergeRequest): List<DiffChunk> = split(mr).chunks

    /** Splits the MR's changes into chunks and reports the diff left past `maxChunks`, per file. */
    fun split(mr: MergeRequest): ChunkResult {
        val changes = mr.changes.filter { it.diffContent != null }
        if (changes.isEmpty()) return ChunkResult(emptyList())

        val totalChars = changes.sumOf { it.diffContent?.length ?: 0 }
        if (totalChars <= maxCharsPerChunk) {
            return ChunkResult(listOf(DiffChunk(index = 0, changes = changes, totalChars = totalChars)))
        }

        val sorted = ReviewUnits
            .group(changes)
            .flatMap { it.changes }
            .flatMap { change -> splitLargeFile(change, overlapChars) }

        val chunks = mutableListOf<DiffChunk>()
        var currentChanges = mutableListOf<FileChange>()
        var currentChars = 0
        var placed = sorted.size

        for ((i, change) in sorted.withIndex()) {
            if (chunks.size >= maxChunks - 1 && currentChanges.isNotEmpty()) {
                chunks.add(
                    DiffChunk(
                        index = chunks.size,
                        changes = currentChanges.toList(),
                        totalChars = currentChars,
                    )
                )
                currentChanges = mutableListOf()
                currentChars = 0
            }

            if (chunks.size >= maxChunks) {
                placed = i
                break
            }

            val changeChars = change.diffContent?.length ?: 0

            if (currentChars + changeChars > maxCharsPerChunk && currentChanges.isNotEmpty()) {
                chunks.add(
                    DiffChunk(
                        index = chunks.size,
                        changes = currentChanges.toList(),
                        totalChars = currentChars,
                    )
                )
                currentChanges = mutableListOf()
                currentChars = 0
            }

            currentChanges.add(change)
            currentChars += changeChars
        }

        if (currentChanges.isNotEmpty() && chunks.size < maxChunks) {
            chunks.add(
                DiffChunk(
                    index = chunks.size,
                    changes = currentChanges.toList(),
                    totalChars = currentChars,
                )
            )
        }

        return ChunkResult(chunks, droppedOf(changes, chunks, sorted.drop(placed)))
    }

    /** Per file with a window in [overflow]: how many of its hunks no chunk carries. */
    private fun droppedOf(
        changes: List<FileChange>,
        chunks: List<DiffChunk>,
        overflow: List<FileChange>,
    ): List<DroppedDiff> {
        val kept = chunks.flatMap { it.changes }.groupBy({ it.path }, { splitHunks(it.diffContent.orEmpty()) })
        return overflow.map { it.path }.distinct().map { path ->
            val total = changes
                .first { it.path == path }
                .diffContent
                .orEmpty()
                .let(::splitHunks)
                .size
            val keptHunks = kept[path]
                .orEmpty()
                .flatten()
                .distinct()
                .size
            DroppedDiff(path, droppedHunks = total - keptHunks, totalHunks = total)
        }
    }

    /** Splits [change] into overlapping hunk windows when its diff exceeds [maxCharsPerChunk]. */
    private fun splitLargeFile(
        change: FileChange,
        overlap: Int
    ): List<FileChange> {
        val diff = change.diffContent ?: return listOf(change)
        val hunks = if (diff.length > maxCharsPerChunk) splitHunks(diff) else emptyList()
        if (hunks.size <= 1) return listOf(change)
        val windows = mutableListOf<FileChange>()
        var start = 0
        while (start < hunks.size) {
            var end = start
            // The window's length once joined: one "\n" between hunks, so no window outgrows the chunk size.
            var size = -1
            while (end < hunks.size && (end == start || size + 1 + hunks[end].length <= maxCharsPerChunk)) {
                size += 1 + hunks[end].length
                end++
            }
            windows.add(change.copy(diffContent = hunks.subList(start, end).joinToString("\n")))
            if (end >= hunks.size) break
            var next = end
            var carried = 0
            var carriedLines = 0
            while (next - 1 > start) {
                val hunk = hunks[next - 1]
                val lines = hunk.count { it == '\n' } + 1
                if (carried + hunk.length > overlap && carriedLines + lines > overlapLines) break
                next--
                carried += hunk.length
                carriedLines += lines
            }
            start = next
        }
        return windows
    }

    companion object {
        private val SECURITY_PATTERNS = listOf(
            "security",
            "auth",
            "crypto",
            "secret",
            ".env",
            "password",
            "credential",
            "permission",
            "rbac",
            "acl",
        )
        private val CONFIG_PATTERNS = listOf(
            "config",
            "settings",
            "application.yml",
            "application.yaml",
            ".properties",
            "docker",
            "k8s",
            "helm",
            "terraform",
        )
        private val TEST_PATTERNS = listOf(
            "test",
            "spec",
            "__tests__",
            "Test.kt",
            "Test.java",
            "_test.go",
            ".test.ts",
        )
        private val GENERATED_PATTERNS = listOf(
            "generated",
            "vendor",
            "node_modules",
            ".lock",
            "package-lock",
            "yarn.lock",
            ".min.js",
            ".min.css",
            "build/",
            "dist/",
        )

        /**
         * Returns a priority score for a file path.
         *
         * Higher scores are placed in earlier chunks so that higher-priority files
         * receive AI analysis first when the chunk limit is reached.
         */
        private const val PRIORITY_SECURITY = 100
        private const val PRIORITY_CONFIG = 80
        private const val PRIORITY_SOURCE = 50
        private const val PRIORITY_TEST = 30
        private const val PRIORITY_GENERATED = 10

        internal fun filePriority(path: String): Int {
            val lower = path.lowercase()
            return when {
                SECURITY_PATTERNS.any { lower.contains(it) } -> PRIORITY_SECURITY
                CONFIG_PATTERNS.any { lower.contains(it) } -> PRIORITY_CONFIG
                GENERATED_PATTERNS.any { lower.contains(it) } -> PRIORITY_GENERATED
                TEST_PATTERNS.any { lower.contains(it) } -> PRIORITY_TEST
                else -> PRIORITY_SOURCE
            }
        }
    }
}

/** Splits a unified diff into hunks, each starting at an `@@` line; a preamble stays with the first hunk. */
internal fun splitHunks(diff: String): List<String> {
    val hunks = mutableListOf<MutableList<String>>()
    for (line in diff.split('\n')) {
        if (hunks.isEmpty() || (line.startsWith("@@") && hunks.last().any { it.startsWith("@@") })) {
            hunks.add(mutableListOf())
        }
        hunks.last().add(line)
    }
    return hunks.map { it.joinToString("\n") }
}

/**
 * [changes] split in two for a retry after a reply cut at the output limit: by file when more than one file has a
 * diff, else the one file's hunks; null when there is a single hunk and nothing to split.
 */
fun halveChanges(changes: List<FileChange>): Pair<List<FileChange>, List<FileChange>>? {
    val withDiff = changes.filter { it.diffContent != null }
    if (withDiff.size > 1) return withDiff.take(withDiff.size / 2) to withDiff.drop(withDiff.size / 2)
    val file = withDiff.singleOrNull() ?: return null
    val hunks = splitHunks(file.diffContent.orEmpty())
    if (hunks.size < 2) return null
    val half = hunks.size / 2
    return listOf(file.copy(diffContent = hunks.take(half).joinToString("\n"))) to
        listOf(file.copy(diffContent = hunks.drop(half).joinToString("\n")))
}

/** [DiffChunker.split]: the chunks, and the diff of each file left past `maxChunks`. */
@Serializable
data class ChunkResult(
    val chunks: List<DiffChunk>,
    val dropped: List<DroppedDiff> = emptyList(),
)

/** A file whose diff is partly or wholly in no chunk: [droppedHunks] of its [totalHunks] were left out. */
@Serializable
data class DroppedDiff(
    val path: String,
    val droppedHunks: Int,
    val totalHunks: Int,
) {
    /** The ledger name: the bare path when no hunk of it was kept, else `path (N of M hunks)`. */
    fun describe(): String = if (droppedHunks >= totalHunks) path else "$path ($droppedHunks of $totalHunks hunks)"
}

/**
 * A chunk of diff content representing a subset of an MR's file changes.
 *
 * @param index Zero-based chunk index within the sequence produced by [DiffChunker.chunk].
 * @param changes The file changes included in this chunk.
 * @param totalChars Sum of [io.karandash.models.FileChange.diffContent] lengths for all changes.
 */
@Serializable
data class DiffChunk(
    val index: Int,
    val changes: List<FileChange>,
    val totalChars: Int,
)

/** Hunks of [changes] (in order) that end past the first [chars] characters of diff: what a diff cut to [chars] loses. */
fun hunksPast(
    changes: List<FileChange>,
    chars: Int,
): Int {
    val lengths = changes.mapNotNull { it.diffContent }.flatMap { diff -> splitHunks(diff).map { it.length + 1 } }
    return lengths.runningReduce { total, length -> total + length }.count { it > chars }
}
