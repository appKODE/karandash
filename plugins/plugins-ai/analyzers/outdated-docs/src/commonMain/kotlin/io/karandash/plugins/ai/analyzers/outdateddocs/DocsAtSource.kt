@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.analyzers.outdateddocs

import io.karandash.api.run.RepositoryFileReader
import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.core.SecretRedactor

/**
 * Documentation near an MR's changed code, read at the source SHA: `docs/<dir>.md` and `docs/<dir>/README.md`
 * for each changed file's directory, the `README.md` of that directory and its ancestors, and `docs/README.md`.
 * Each doc goes through [SecretRedactor] before it can reach a prompt.
 */
internal object DocsAtSource {
    const val MAX_DOCS = 8
    const val MAX_DOC_CHARS = 4000

    // limit: conventional names only, the platform reader cannot list docs/**; add a tree listing to read every doc
    fun candidates(codePaths: List<String>): List<String> =
        codePaths
            .flatMap { path ->
                val dirs = path.split('/').dropLast(1)
                val docs = dirs.lastOrNull()?.let { listOf("docs/$it.md", "docs/$it/README.md") }.orEmpty()
                docs + (dirs.size downTo 0).map { n -> (dirs.take(n) + "README.md").joinToString("/") }
            }.plus("docs/README.md")
            .distinct()

    /** Path to content of the [candidates] that exist at [mr]'s source SHA; empty without a [reader]. */
    suspend fun load(
        mr: MergeRequest,
        codePaths: List<String>,
        reader: RepositoryFileReader?,
    ): Map<String, String> {
        reader ?: return emptyMap()
        val ref = mr.diffRefs?.headSha ?: mr.sourceBranch
        val found = linkedMapOf<String, String>()
        for (path in candidates(codePaths)) {
            if (found.size == MAX_DOCS) break
            reader.read(ref, path)?.let { found[path] = SecretRedactor.redactText(it).take(MAX_DOC_CHARS) }
        }
        return found
    }
}
