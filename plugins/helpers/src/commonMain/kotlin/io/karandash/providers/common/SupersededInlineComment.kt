package io.karandash.providers.common

import io.karandash.api.support.rethrowCancellation
import io.karandash.sdk.Logger

/**
 * Edit-in-place cleanup for platforms whose review threads Karandash does not resolve (GitHub REST, Gitea before 1.26, AWS
 * CodeCommit; Bitbucket also resolves after editing): an earlier run's marked inline comment is kept and gets [NOTE] on top instead of being deleted and
 * re-posted. The original text and marker stay, so dismissal reactions on it are still read back.
 */
object SupersededInlineComment {
    const val NOTE = "> **Outdated:** superseded by a newer Karandash review."

    /** Whether [body] is a marked comment from an earlier run that has not been marked superseded yet. */
    fun isPending(
        body: String?,
        marker: String,
    ): Boolean = body != null && marker in body && !body.startsWith(NOTE)

    /** [body] with the superseded note on top. */
    fun body(body: String): String = "$NOTE\n\n$body"

    /**
     * Runs [mark] on each of [pending]; a failure on one (say a human reply that quotes the marker and cannot be
     * edited by the bot) is logged at warn and does not stop the rest. Returns how many were marked.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> markEach(
        pending: List<T>,
        logger: Logger,
        mark: suspend (T) -> Unit,
    ): Int {
        var marked = 0
        for (comment in pending) {
            try {
                mark(comment)
                marked++
            } catch (e: Exception) {
                // boundary: platform API call per comment; a client error (ISE from BaseProviderClient) must not stop the rest
                e.rethrowCancellation()
                logger.warn("Could not mark an earlier inline comment as superseded: ${e.message}")
            }
        }
        return marked
    }

    /**
     * Marks the earlier-run comments among [items] superseded: those whose [body] is [isPending] for [marker] and whose
     * [path] is [inScope] of [filePaths], each passed to [mark] via [markEach]. Returns how many were marked.
     */
    suspend fun <T> supersede(
        items: List<T>,
        marker: String,
        filePaths: Set<String>?,
        logger: Logger,
        body: (T) -> String?,
        path: (T) -> String?,
        mark: suspend (T) -> Unit,
    ): Int = markEach(items.filter { isPending(body(it), marker) && inScope(path(it), filePaths) }, logger, mark)

    /** Whether a comment on [path] is in scope of a cleanup limited to [filePaths] (`null`: every file). */
    fun inScope(
        path: String?,
        filePaths: Set<String>?,
    ): Boolean = filePaths == null || path in filePaths
}
