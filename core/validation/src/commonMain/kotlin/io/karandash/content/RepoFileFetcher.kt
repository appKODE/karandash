package io.karandash.content

/**
 * Fetches a file from the repository being validated, for [io.karandash.sdk.content.ContentSource.RepoFile].
 *
 * Failures are returned as [Result.failure] wrapping
 * [io.karandash.api.exception.ContentResolveException] rather than thrown.
 */
fun interface RepoFileFetcher {
    /**
     * @param path Repo-relative file path.
     * @param ref Git ref (branch, tag, SHA); null means the checked-out `HEAD`.
     */
    suspend fun fetch(
        path: String,
        ref: String?,
    ): Result<String>
}
