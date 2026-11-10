package io.karandash.enrichment.localgit

import io.karandash.models.support.KarandashJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Counts commit contributions per author for the given file paths.
 *
 * Uses `git log --format=%H%x09%aN <%aE> --since=<period> HEAD -- <paths>` chunked by
 * [DEFAULT_CHUNK_SIZE] to avoid argument-list overflow, counts each commit once across chunks, and
 * merges the per-author counts by email address (the same `  <count>\t<Name> <<email>>` shape
 * `git shortlog -sne` prints, see [parse]).
 *
 * Returns empty on: null/blank working dir, empty file list, git unavailable, or
 * non-zero exit code.
 */
internal object ContributorAnalyzer {
    const val DEFAULT_PERIOD: String = "6 months ago"
    const val DEFAULT_CHUNK_SIZE: Int = 50

    @Serializable
    data class ContributorStats(
        val name: String,
        val email: String,
        val commits: Int
    )

    suspend fun analyze(
        workingDir: String?,
        filePaths: List<String>,
        period: String = DEFAULT_PERIOD,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        budget: io.karandash.api.platform.SubprocessBudget? = null,
        logger: io.karandash.sdk.Logger? = null,
    ): Map<String, ContributorStats> {
        if (workingDir.isNullOrBlank() || filePaths.isEmpty()) return emptyMap()
        val effectivePeriod = period.ifBlank { DEFAULT_PERIOD }
        val effectiveChunk = chunkSize.coerceAtLeast(1)

        // A commit touching files in several chunks is listed once per chunk; key by hash to count it once.
        val authorByCommit = mutableMapOf<String, String>()
        for (chunk in filePaths.chunked(effectiveChunk)) {
            val args = buildList {
                add("log")
                add("--format=%H%x09%aN <%aE>")
                add("--since=$effectivePeriod")
                add("HEAD")
                add("--")
                addAll(chunk)
            }
            val output = GitCliHelper.run(workingDir, args, budget = budget, logger = logger) ?: continue
            for (line in output.lineSequence()) {
                val tabIdx = line.indexOf('\t')
                if (tabIdx > 0) authorByCommit[line.substring(0, tabIdx)] = line.substring(tabIdx + 1)
            }
        }
        val shortlog = authorByCommit.values
            .groupingBy { it }
            .eachCount()
            .entries
            .joinToString("\n") { "${it.value}\t${it.key}" }
        return parse(shortlog)
    }

    /**
     * Parses `git shortlog -sne` output into a contributor map keyed by email.
     *
     * Each non-blank line has the format `   <count>\t<Name> <<email>>`.
     * Lines missing a tab, count, or `<email>` angle brackets are silently skipped.
     * Commit counts are summed across all names used with one email; the reported name is the one
     * with the most commits (ties: lexicographically smallest).
     */
    internal fun parse(output: String): Map<String, ContributorStats> {
        val names = mutableMapOf<String, MutableMap<String, Int>>()
        accumulate(names, output)
        return resolve(names)
    }

    private fun accumulate(
        names: MutableMap<String, MutableMap<String, Int>>,
        output: String
    ) {
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val tabIdx = trimmed.indexOf('\t')
            if (tabIdx < 0) continue
            val count = trimmed.substring(0, tabIdx).trim().toIntOrNull() ?: continue
            val nameEmail = trimmed.substring(tabIdx + 1).trim()
            val emailStart = nameEmail.lastIndexOf('<')
            val emailEnd = nameEmail.lastIndexOf('>')
            if (emailStart < 0 || emailEnd <= emailStart) continue
            val email = nameEmail.substring(emailStart + 1, emailEnd).trim()
            if (email.isEmpty()) continue
            val name = nameEmail.substring(0, emailStart).trim()
            val perName = names.getOrPut(email) { mutableMapOf() }
            perName[name] = (perName[name] ?: 0) + count
        }
    }

    private fun resolve(names: Map<String, Map<String, Int>>): Map<String, ContributorStats> =
        names.mapValues { (email, perName) ->
            val best = perName.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first()
            ContributorStats(name = best.key, email = email, commits = perName.values.sum())
        }

    /**
     * `{"contributors": [{name, email, commits}], "period": <period>}` - contributors by commit count
     * descending, then by email for a stable order.
     */
    internal fun toJson(
        stats: Map<String, ContributorStats>,
        period: String,
    ): JsonElement {
        val sorted = stats.values.sortedWith(compareByDescending<ContributorStats> { it.commits }.thenBy { it.email })
        return buildJsonObject {
            put("contributors", KarandashJson.Default.encodeToJsonElement(ListSerializer(ContributorStats.serializer()), sorted))
            put("period", period)
        }
    }
}
