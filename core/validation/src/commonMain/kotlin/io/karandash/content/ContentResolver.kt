package io.karandash.content

import io.karandash.api.exception.ContentResolveException
import io.karandash.api.support.rethrowCancellation
import io.karandash.config.AuthConfig
import io.karandash.http.HostCredentials
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.platform.PathSecurity
import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.exists
import io.karandash.platform.resolveUserPath
import io.karandash.sdk.content.ContentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

/**
 * Resolves [ContentSource] instances to their string content.
 *
 * Handles inline values, local file loading, remote HTTP fetching, and parallel
 * multi-source aggregation. [ContentSource.RepoFile] is read through [repoFileFetcher]; without
 * one it fails with [ContentResolveException]. Every failure is a [ContentResolveException].
 *
 * [ContentSource.LocalFile] paths are confined to [configDir]: absolute paths and `..` segments
 * that leave it are rejected, as are symlinks whose target lies outside it. The shared
 * `resolveUserPath` stays unconfined on purpose - `{ file: "../x" }` config values and `../../`
 * `extends:` entries are operator-written and legitimately climb out of the config directory.
 *
 * @param configDir Directory that relative file paths resolve against.
 * @param authRegistry Named auth configurations for remote/local file sources. An entry with `baseUrl` is only
 *   sent to that host (and port); a source on another host fails with [ContentResolveException].
 * @param fetcher Suspend function that performs HTTP GET and returns body as string.
 *   Injectable for testing; supply via `core-http`'s `ProviderHttpClientFactory` at CLI layer.
 * @param logger Logger for diagnostic output.
 * @param repoFileFetcher Reads [ContentSource.RepoFile] sources; wired at the CLI layer
 *   ([GitRepoFileFetcher] over the local checkout).
 */
class ContentResolver(
    private val configDir: String,
    private val authRegistry: AuthRegistry = AuthRegistry.EMPTY,
    private val fetcher: RemoteContentFetcher? = null,
    private val logger: Logger = NoOpLogger,
    private val repoFileFetcher: RepoFileFetcher? = null,
) {
    /** Resolve any [ContentSource] to its string content. */
    suspend fun resolve(source: ContentSource): String =
        when (source) {
            is ContentSource.Inline -> source.value
            is ContentSource.LocalFile -> resolveLocalFile(source)
            is ContentSource.RemoteFile -> resolveRemoteFile(source)
            is ContentSource.RepoFile -> resolveRepoFile(source)
            is ContentSource.Multiple -> resolveMultiple(source)
        }

    private suspend fun resolveLocalFile(source: ContentSource.LocalFile): String {
        val path = containedPath(source.path)
        logger.debug("Resolving local file: $path")
        return withContext(Dispatchers.IO) { LocalContentReader.read(path, source.path) }
    }

    /** Resolves [raw] against [configDir] and rejects anything that ends up outside it. */
    private fun containedPath(raw: String): Path {
        val base = PathSecurity.normalizeLexical(configDir)
        val realBase = PathSecurity.normalizeLexical(Path(configDir).absolutePathOrSelf().toString())
        val candidate = PathSecurity.normalizeLexical(resolveUserPath(raw, Path(configDir)).toString())
        val path = Path(candidate)
        // lexical check first, then the real (symlink-resolved) location when the file exists
        val inside = (isUnder(candidate, base) || isUnder(candidate, realBase)) &&
            (!path.exists() || isUnder(PathSecurity.normalizeLexical(path.absolutePathOrSelf().toString()), realBase))
        if (!inside) {
            throw ContentResolveException(
                sourceType = SOURCE_TYPE_LOCAL,
                reference = raw,
                message = "Local file '$raw' is outside the config directory; only files under it can be read.",
            )
        }
        return path
    }

    @Suppress("TooGenericExceptionCaught") // boundary: fetcher can throw IOException or Ktor-specific errors
    private suspend fun resolveRemoteFile(source: ContentSource.RemoteFile): String {
        val activeFetcher = fetcher
            ?: throw ContentResolveException(
                sourceType = SOURCE_TYPE_REMOTE,
                reference = source.url,
                message = "Remote file resolution is not available in this context. " +
                    "A RemoteContentFetcher must be wired at the CLI layer.",
            )

        val authConfig = source.auth?.let { name ->
            if (!authRegistry.has(name)) {
                throw ContentResolveException(
                    sourceType = SOURCE_TYPE_REMOTE,
                    reference = source.url,
                    message = "Unknown auth reference '$name' for '${source.url}': add it under the top-level 'auth:' block.",
                )
            }
            authRegistry.resolve(name).also { config -> requireBoundHost(name, config, source.url) }
        }

        logger.debug("Resolving remote file: ${source.url}")

        return try {
            activeFetcher.fetch(source.url, authConfig)
        } catch (e: ContentResolveException) {
            throw e
        } catch (e: Exception) {
            // boundary: injected remote fetcher can fail in any way
            e.rethrowCancellation()
            throw ContentResolveException(
                sourceType = SOURCE_TYPE_REMOTE,
                reference = source.url,
                message = "Failed to fetch remote file '${source.url}': ${e.message}",
                cause = e,
            )
        }
    }

    /** An entry with `baseUrl` is keyed for that host: refuse to send it anywhere else. */
    private fun requireBoundHost(
        name: String,
        config: AuthConfig,
        url: String,
    ) {
        val baseUrl = config.baseUrl ?: return
        if (HostCredentials.sameHost(url, baseUrl)) return
        throw ContentResolveException(
            sourceType = SOURCE_TYPE_REMOTE,
            reference = url,
            message = "Auth '$name' is bound to the host of 'auth.$name.baseUrl' ($baseUrl); not sending it to '$url'.",
        )
    }

    private suspend fun resolveRepoFile(source: ContentSource.RepoFile): String {
        val activeFetcher = repoFileFetcher
            ?: throw ContentResolveException(
                sourceType = SOURCE_TYPE_REPO,
                reference = source.path,
                message = "RepoFile resolution is not available in this context. " +
                    "A RepoFileFetcher must be wired at the CLI layer. " +
                    "Path: '${source.path}'${source.ref?.let { ", ref: '$it'" } ?: ""}.",
            )
        logger.debug("Resolving repo file: ${source.path} at ${source.ref ?: "HEAD"}")
        return activeFetcher.fetch(source.path, source.ref).getOrElse { e ->
            e.rethrowCancellation()
            throw e as? ContentResolveException ?: ContentResolveException(
                sourceType = SOURCE_TYPE_REPO,
                reference = source.path,
                message = "Failed to read repo file '${source.path}': ${e.message}",
                cause = e,
            )
        }
    }

    private suspend fun resolveMultiple(source: ContentSource.Multiple): String {
        val semaphore = Semaphore(MULTIPLE_CONCURRENCY_LIMIT)
        return coroutineScope {
            source.sources
                .map { child ->
                    async {
                        semaphore.withPermit { resolve(child) }
                    }
                }.map { it.await() }
        }.joinToString(source.separator)
    }

    companion object {
        /** Maximum file size for local file reads: 1 MB. */
        const val MAX_FILE_SIZE_BYTES: Int = LocalContentReader.MAX_FILE_SIZE_BYTES

        private const val MULTIPLE_CONCURRENCY_LIMIT = 10

        private const val SOURCE_TYPE_LOCAL = "LocalFile"
        private const val SOURCE_TYPE_REMOTE = "RemoteFile"
        private const val SOURCE_TYPE_REPO = "RepoFile"

        private fun isUnder(
            path: String,
            base: String,
        ): Boolean =
            when {
                base.isEmpty() -> !path.startsWith("..") && !path.startsWith("/")
                else -> path == base || path.startsWith(if (base.endsWith('/')) base else "$base/")
            }
    }
}
