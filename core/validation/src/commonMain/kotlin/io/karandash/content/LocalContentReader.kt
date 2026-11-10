package io.karandash.content

import io.karandash.api.exception.ContentResolveException
import io.karandash.platform.exists
import io.karandash.platform.fileSize
import io.karandash.platform.isFile
import io.karandash.platform.readBytes
import kotlinx.io.IOException
import kotlinx.io.files.Path

/**
 * The single local-file I/O path shared by [ContentResolver] (`ContentSource.LocalFile`) and the
 * config pipeline's `{ file: "..." }` resolution (`ExternalValueResolver`).
 *
 * Path confinement is the caller's concern: [ContentResolver] confines plugin reads to the config
 * directory, `{ file: }` values are operator-written and may climb out of it.
 *
 * Blocking; suspend callers wrap it in `withContext(Dispatchers.IO)`.
 */
object LocalContentReader {
    /** Maximum file size for local file reads: 1 MB. */
    const val MAX_FILE_SIZE_BYTES: Int = 1 * 1024 * 1024

    private const val BYTES_PER_MB = 1024 * 1024
    private const val SOURCE_TYPE = "LocalFile"

    /**
     * Read [path] as UTF-8 text.
     *
     * @param reference The user-facing reference (as written in config) used in error messages.
     * @throws ContentResolveException if the file is missing, not a regular file, unreadable, or too large.
     */
    fun read(
        path: Path,
        reference: String,
    ): String {
        if (!path.exists() || !path.isFile()) {
            throw ContentResolveException(
                sourceType = SOURCE_TYPE,
                reference = reference,
                message = "Local file not found or is not a regular file: $reference",
            )
        }
        if (path.fileSize() > MAX_FILE_SIZE_BYTES) throw tooLarge(reference, path.fileSize())
        val bytes = try {
            path.readBytes()
        } catch (e: IOException) {
            throw ContentResolveException(
                sourceType = SOURCE_TYPE,
                reference = reference,
                message = "Failed to read local file '$reference': ${e.message}",
                cause = e,
            )
        }
        // the file may have grown between the size check and the read
        if (bytes.size > MAX_FILE_SIZE_BYTES) throw tooLarge(reference, bytes.size.toLong())
        return bytes.decodeToString()
    }

    private fun tooLarge(
        reference: String,
        size: Long,
    ) = ContentResolveException(
        sourceType = SOURCE_TYPE,
        reference = reference,
        message = "Local file '$reference' exceeds maximum size of ${MAX_FILE_SIZE_BYTES / BYTES_PER_MB}MB ($size bytes).",
    )
}
