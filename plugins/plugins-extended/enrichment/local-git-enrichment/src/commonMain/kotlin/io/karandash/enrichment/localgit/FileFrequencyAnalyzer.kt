package io.karandash.enrichment.localgit

import io.karandash.api.platform.SubprocessBudget
import io.karandash.sdk.Logger

/**
 * Counts how often each file has been touched in the recent history of the
 * repository. Produces a `Map<path, commit-count>` used by the AI file
 * priority scorer as the "hotspot" signal.
 *
 * Uses a single `git log --format= --name-only --since=<period> -- <paths>`
 * invocation and tallies the result in-process. When git is missing, the
 * repo is shallow, or no commits fall inside the window, returns empty.
 */
internal object FileFrequencyAnalyzer {
    /** Default look-back window if the user has not configured one. */
    const val DEFAULT_PERIOD: String = "6 months ago"

    /** Maximum number of file paths to feed into a single git log invocation. */
    const val DEFAULT_MAX_PATHSPECS: Int = 200

    /**
     * @param workingDir Repository root; null or empty disables the analyzer.
     * @param filePaths Paths whose frequencies should be computed.
     * @param period Git-compatible relative or absolute time string
     *     (e.g. `"6 months ago"`, `"2024-01-01"`). Falls back to
     *     [DEFAULT_PERIOD] on blank input.
     * @param maxPathspecs Soft limit on how many paths to include in a single
     *     `git log` invocation.
     * @param budget Optional per-run [SubprocessBudget]. When provided, the
     *     underlying git call is skipped once the budget is exhausted.
     * @param logger Optional logger used for budget-rejection warnings.
     * @return file-path → commit-count. Empty map on error or zero commits.
     */
    suspend fun analyze(
        workingDir: String?,
        filePaths: List<String>,
        period: String = DEFAULT_PERIOD,
        maxPathspecs: Int = DEFAULT_MAX_PATHSPECS,
        budget: SubprocessBudget? = null,
        logger: Logger? = null,
    ): Map<String, Int> {
        if (workingDir.isNullOrBlank() || filePaths.isEmpty()) return emptyMap()
        val effectivePeriod = period.ifBlank { DEFAULT_PERIOD }
        val trimmedPaths = filePaths.take(maxPathspecs.coerceAtLeast(1))

        val args = buildList {
            add("log")
            add("--format=")
            add("--name-only")
            add("--since=$effectivePeriod")
            add("--")
            addAll(trimmedPaths)
        }
        val output = GitCliHelper.run(workingDir, args, budget = budget, logger = logger) ?: return emptyMap()
        return tally(output, trimmedPaths.toSet())
    }

    /** Aggregate the `--name-only` rows into a per-file count. */
    internal fun tally(
        output: String,
        knownPaths: Set<String>
    ): Map<String, Int> {
        if (output.isBlank()) return emptyMap()
        val counts = mutableMapOf<String, Int>()
        for (line in output.lineSequence()) {
            val path = line.trim()
            if (path.isEmpty()) continue
            if (knownPaths.isNotEmpty() && path !in knownPaths) continue
            counts[path] = (counts[path] ?: 0) + 1
        }
        return counts.toMap()
    }
}
