package io.karandash.content

import io.karandash.api.exception.ContentResolveException
import io.karandash.api.module.PlatformServiceModule
import io.karandash.platform.GitArgSanitizer
import io.karandash.platform.GitRunner

/**
 * [RepoFileFetcher] that reads files from the local checkout's git objects ([GitRunner.readBlob]): a blob over
 * [PlatformServiceModule.MAX_FILE_CONTENT_BYTES] or a binary one is a failure, not loaded.
 *
 * In CI the merge request's source branch is checked out, so `HEAD` is the MR head.
 *
 * @param workingDir Directory inside the git work tree.
 */
class GitRepoFileFetcher(
    private val workingDir: String,
    private val git: GitRunner = GitRunner.Default,
) : RepoFileFetcher {
    override suspend fun fetch(
        path: String,
        ref: String?,
    ): Result<String> {
        val revision = ref ?: DEFAULT_REF
        val safePath = try {
            GitArgSanitizer.sanitizePath(path)
        } catch (e: IllegalArgumentException) {
            return failure(path, "Invalid repo file path '$path': ${e.message}", e)
        }
        val refError = validateRef(revision)
        if (refError != null) return failure(path, "Invalid git ref for repo file '$path': $refError")

        val spec = "$revision:$safePath"
        val cap = PlatformServiceModule.MAX_FILE_CONTENT_BYTES
        return when (val blob = git.readBlob(revision, safePath, cap, workingDir)) {
            is GitRunner.Blob.Text -> Result.success(blob.content)
            is GitRunner.Blob.TooLarge -> failure(path, "$spec is ${blob.bytes} bytes, over the $cap-byte cap")
            GitRunner.Blob.Binary -> failure(path, "$spec is a binary file")
            is GitRunner.Blob.Failed -> failure(path, "git cat-file $spec failed (exit ${blob.exitCode}): ${blob.error}")
        }
    }

    /** Returns a reason when [ref] is unsafe to pass to git, or null when it is acceptable. */
    private fun validateRef(ref: String): String? =
        when {
            ref.isBlank() -> "ref is blank"
            ref.startsWith("-") -> "ref must not start with '-': $ref"
            else -> unsafeRefReason(ref)
        }

    private fun unsafeRefReason(ref: String): String? =
        try {
            GitArgSanitizer.sanitizeUserArgs(listOf(ref))
            null
        } catch (e: IllegalArgumentException) {
            e.message ?: "ref is unsafe: $ref"
        }

    private fun failure(
        path: String,
        message: String,
        cause: Throwable? = null,
    ): Result<String> = Result.failure(ContentResolveException(SOURCE_TYPE, path, message, cause))

    private companion object {
        const val DEFAULT_REF = "HEAD"
        const val SOURCE_TYPE = "RepoFile"
    }
}
