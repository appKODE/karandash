package io.karandash.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * The one way karandash runs `git`: every invocation is bounded by a timeout ([ProcessTimeouts.DEFAULT_SECONDS]
 * unless given), a missing git or a non-zero exit is `null` rather than an exception, and blob reads go through
 * [readBlob] (size cap before the content is read, binary sniff, `--end-of-options` before the object spec).
 *
 * Arguments are not sanitized here: callers validate user-derived values with [GitArgSanitizer].
 *
 * @param execute runs a command vector `(command, workingDir, timeoutSeconds)`; a seam for tests.
 */
class GitRunner(
    private val execute: (command: List<String>, workingDir: String?, timeoutSeconds: Int) -> ProcessResult =
        { command, workingDir, timeoutSeconds ->
            PlatformProcess.execute(command, workingDir, timeoutSeconds = timeoutSeconds)
        },
) {
    /** `git <args>` in [workingDir]; `null` when git cannot be started. Blocking: prefer [git] from coroutines. */
    fun runSync(
        args: List<String>,
        workingDir: String? = null,
        timeoutSeconds: Int = ProcessTimeouts.DEFAULT_SECONDS,
    ): ProcessResult? =
        try {
            execute(listOf(GIT) + args, workingDir, timeoutSeconds)
        } catch (e: CancellationException) {
            throw e
        } catch (
            // boundary: git may be missing or not startable; callers treat it as "no answer"
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            null
        }

    /** Stdout of `git <args>`, untrimmed; `null` on a non-zero exit, a timeout, or when git cannot run. Blocking. */
    fun gitSync(
        args: List<String>,
        workingDir: String? = null,
        timeoutSeconds: Int = ProcessTimeouts.DEFAULT_SECONDS,
    ): String? = runSync(args, workingDir, timeoutSeconds)?.takeIf { it.isSuccess }?.output

    /** [gitSync] on [Dispatchers.IO]. */
    suspend fun git(
        args: List<String>,
        workingDir: String? = null,
        timeoutSeconds: Int = ProcessTimeouts.DEFAULT_SECONDS,
    ): String? = withContext(Dispatchers.IO) { gitSync(args, workingDir, timeoutSeconds) }

    /**
     * The blob at `[rev]:[path]`. Its size is asked first (`cat-file -s`), so a blob over [maxBytes] is never read;
     * content with a NUL in its first [BINARY_SNIFF_CHARS] characters is [Blob.Binary].
     */
    suspend fun readBlob(
        rev: String,
        path: String,
        maxBytes: Int,
        workingDir: String? = null,
    ): Blob =
        withContext(Dispatchers.IO) {
            val spec = "$rev:$path"
            val sized = runSync(listOf("cat-file", "-s", END_OF_OPTIONS, spec), workingDir)
                ?: return@withContext Blob.Failed(-1, "git could not be run")
            if (!sized.isSuccess) return@withContext Blob.Failed(sized.exitCode, sized.error.trim())
            val size = sized.output.trim().toLongOrNull() ?: return@withContext Blob.Failed(0, "unparsable blob size")
            if (size > maxBytes) return@withContext Blob.TooLarge(size)
            val read = runSync(listOf("cat-file", "blob", END_OF_OPTIONS, spec), workingDir)
                ?: return@withContext Blob.Failed(-1, "git could not be run")
            when {
                !read.isSuccess -> Blob.Failed(read.exitCode, read.error.trim())
                '\u0000' in read.output.take(BINARY_SNIFF_CHARS) -> Blob.Binary
                else -> Blob.Text(read.output)
            }
        }

    /** Outcome of [readBlob]. */
    @Serializable
    sealed interface Blob {
        /** The blob's text, unmodified. */
        @Serializable
        data class Text(
            val content: String
        ) : Blob

        /** The blob is [bytes] long, over the cap; its content was not read. */
        @Serializable
        data class TooLarge(
            val bytes: Long
        ) : Blob

        /** The blob has a NUL byte near its start. */
        @Serializable
        data object Binary : Blob

        /** git failed: a missing object, a bad rev, or git not runnable (exit `-1`). */
        @Serializable
        data class Failed(
            val exitCode: Int,
            val error: String
        ) : Blob

        /** The text when this is [Text], else `null`. */
        val textOrNull: String? get() = (this as? Text)?.content
    }

    companion object {
        /** The runner over [PlatformProcess]. */
        val Default: GitRunner = GitRunner()

        /** Characters of a blob scanned for a NUL to call it binary. */
        const val BINARY_SNIFF_CHARS: Int = 8 * 1024

        private const val GIT = "git"
        private const val END_OF_OPTIONS = "--end-of-options"
    }
}
