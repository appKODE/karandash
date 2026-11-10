package io.karandash.download

import io.karandash.api.exception.PluginSecurityException
import io.karandash.api.support.rethrowCancellation
import io.karandash.platform.createDirectories
import io.karandash.platform.createTempFileInDir
import io.karandash.platform.deleteIfExists
import io.karandash.platform.moveFile
import io.karandash.platform.safeParent
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * Downloads remote artifacts over HTTPS.
 *
 * Security constraints:
 * - Only HTTPS URLs are accepted (HTTP is rejected).
 * - Downloads to a temporary file first, then atomically moves to the target path.
 * - Signatures are not checked here: `PluginInstallUpdateEngine` (plugins/registry) fetches and verifies the
 *   `.sig` of a downloaded plugin.
 *
 * @param httpClient The Ktor [HttpClient] to use for fetching.
 */
class ArtifactDownloader(
    private val httpClient: HttpClient,
) {
    companion object {
        /**
         * Maximum allowed artifact size (50 MB).
         * Prevents DoS attacks via arbitrarily large downloads.
         */
        private const val BYTES_PER_KB = 1024
        private const val MAX_PLUGIN_SIZE_BYTES = 50 * BYTES_PER_KB * BYTES_PER_KB.toLong()
        private const val DOWNLOAD_BUFFER_SIZE = 8192
    }

    /**
     * Fetch a remote artifact.
     *
     * @param url The HTTPS URL of the artifact.
     * @param targetPath The local [Path] where the artifact should be stored.
     * @return [Result] containing the [Path] on success, or the exception on failure.
     */
    @Suppress("TooGenericExceptionCaught") // Cleanup-on-error and Result.failure wrapping
    suspend fun fetch(
        url: String,
        targetPath: Path,
    ): Result<Path> {
        return try {
            // Enforce HTTPS-only
            val parsed = Url(url)
            if (parsed.protocol != URLProtocol.HTTPS) {
                return Result.failure(
                    PluginSecurityException(
                        "Only HTTPS URLs are allowed for remote plugins. Got: ${parsed.protocol.name}://"
                    )
                )
            }

            val parentPath = targetPath.safeParent
            if (parentPath != null) {
                createDirectories(parentPath)
            }

            val tempDir = parentPath ?: SystemTemporaryDirectory
            val tempFile = createTempFileInDir(tempDir, "karandash-plugin-", ".jar.tmp")

            try {
                downloadToFile(url, tempFile)
                moveFile(tempFile, targetPath, overwrite = true)
                Result.success(targetPath)
            } catch (e: Exception) {
                // boundary: download and move; failure becomes Result.failure
                e.rethrowCancellation()
                deleteIfExists(tempFile)
                throw e
            }
        } catch (e: Exception) {
            // boundary: download and move; failure becomes Result.failure
            e.rethrowCancellation()
            Result.failure(e)
        }
    }

    private suspend fun downloadToFile(
        url: String,
        tempFile: Path,
    ) {
        val response = httpClient.get(url)

        val finalUrl = response.request.url
        if (finalUrl.protocol != URLProtocol.HTTPS) {
            deleteIfExists(tempFile)
            throw PluginSecurityException(
                "Redirect to non-HTTPS URL detected. Original: $url, Final: ${finalUrl.protocol.name}://${finalUrl.host}"
            )
        }

        if (!response.status.isSuccess()) {
            deleteIfExists(tempFile)
            error(
                "Failed to download plugin from $url: HTTP ${response.status.value} ${response.status.description}"
            )
        }

        val contentLength = response.contentLength()
        if (contentLength != null && contentLength > MAX_PLUGIN_SIZE_BYTES) {
            deleteIfExists(tempFile)
            throw PluginSecurityException(
                "Plugin too large: $contentLength bytes (max: $MAX_PLUGIN_SIZE_BYTES bytes / ${MAX_PLUGIN_SIZE_BYTES / BYTES_PER_KB / BYTES_PER_KB} MB)"
            )
        }

        val channel = response.bodyAsChannel()
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
        var totalBytesRead = 0L

        SystemFileSystem.sink(tempFile).buffered().use { sink ->
            while (!channel.isClosedForRead) {
                val bytesRead = channel.readAvailable(buffer, 0, buffer.size)
                if (bytesRead == -1) break

                totalBytesRead += bytesRead
                if (totalBytesRead > MAX_PLUGIN_SIZE_BYTES) {
                    sink.close()
                    deleteIfExists(tempFile)
                    throw PluginSecurityException(
                        "Plugin download exceeded maximum size: $MAX_PLUGIN_SIZE_BYTES bytes"
                    )
                }

                sink.write(buffer, 0, bytesRead)
            }
            sink.flush()
        }
    }
}
