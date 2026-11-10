package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.config.KarandashLimits
import io.karandash.duration.KarandashDuration
import io.karandash.http.HttpAuth
import io.karandash.http.NetworkPolicy
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.http.SsrfHostKind
import io.karandash.http.SsrfUrlValidator
import io.karandash.http.applyAuth
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.logger.debug
import io.karandash.manifest.ConfigSource
import io.karandash.platform.PathSecurity
import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.canRead
import io.karandash.platform.currentWorkingDirectory
import io.karandash.platform.exists
import io.karandash.platform.isAbsolutePath
import io.karandash.platform.readText
import io.karandash.platform.tempDirectory
import io.karandash.security.UrlSanitizer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable

/**
 * Result from an HTTP fetch with caching headers.
 *
 * @property statusCode HTTP status code (200, 304, etc.)
 * @property content Response body as text (empty for 304 Not Modified)
 * @property etag ETag header value for cache validation
 * @property lastModified Last-Modified header value
 */
@Serializable
data class FetchResult(
    val statusCode: Int,
    val content: String,
    val etag: String? = null,
    val lastModified: String? = null
)

/**
 * Host policy applied by [RemoteConfigFetcher] before every HTTP(S) request.
 *
 * Cloud-metadata and link-local hosts are always rejected; loopback needs `allowLocalhost` in both modes.
 */
enum class SsrfMode {
    /** Operator-written sources (`extends:`, `{ file: }`): private and dotless hosts are fetched with a warning. */
    OPERATOR,

    /** Plugin-requested content: private and dotless hosts are rejected unless `allowLocalhost` or `allowPrivateNetwork`. */
    STRICT,
}

/**
 * Fetches remote configuration files via HTTP or local filesystem.
 *
 * Supports both HTTP(S) URLs and local file paths. Local paths are validated
 * against path traversal attacks so they stay within the project root.
 *
 * Every HTTP(S) request first passes the [ssrfMode] host policy (see [SsrfMode]); anonymous
 * `http://` is fetched with a one-time warning per URL. Redirects are never followed and
 * response bodies are capped at [MAX_BODY_BYTES] without buffering more than the cap.
 *
 * @param explicitClient Optional pre-configured HTTP client for remote fetches. It is re-configured
 *   with the same timeout and no-redirect settings as the default client.
 *   When null (default), a [ProviderHttpClientFactory] client (run diagnostics, client ledger, retry settings)
 *   with the fetch timeout and redirects off is created lazily on first HTTP request, avoiding platform engine initialisation for local-only configs.
 * @param ssrfMode Host policy: [SsrfMode.OPERATOR] for operator-written `extends:`, [SsrfMode.STRICT] for plugin content.
 * @param allowLocalhost Permits loopback hosts (and private/dotless hosts in [SsrfMode.STRICT]).
 * @param logger Receives the SSRF and plain-HTTP warnings.
 */
class RemoteConfigFetcher private constructor(
    private val explicitClient: HttpClient?,
    private val ssrfMode: SsrfMode,
    private val allowLocalhost: Boolean,
    private val logger: Logger,
    /** Request/connect timeout: the inherited `limits.fetchTimeout` (clamped). */
    private val fetchTimeoutMs: Long,
    /** URLs (sanitized) already warned about this run - one warning per URL, shared with derived fetchers. */
    private val warnedUrls: MutableStateFlow<Set<String>>,
) {
    constructor(
        explicitClient: HttpClient? = null,
        ssrfMode: SsrfMode = SsrfMode.OPERATOR,
        allowLocalhost: Boolean = false,
        logger: Logger = NoOpLogger,
        fetchTimeoutMs: Long = KarandashLimits.FETCH_TIMEOUT_MS,
    ) : this(explicitClient, ssrfMode, allowLocalhost, logger, fetchTimeoutMs, MutableStateFlow(emptySet()))

    /** Fetchers made by [withSettings], by (allowLocalhost, fetchTimeoutMs); closed with this one. */
    private val derived = MutableStateFlow<Map<Pair<Boolean, Long>, RemoteConfigFetcher>>(emptyMap())

    companion object {
        /** Largest remote config / content body accepted (1 MiB). */
        const val MAX_BODY_BYTES: Long = 1024L * 1024L

        private const val HTTP_NOT_MODIFIED = 304
        private const val HTTPS_PREFIX = "https://"
        private const val AUTH_HINT = " Check that the auth token is valid and has read access."
        private const val NO_AUTH_HINT = " This resource may require authentication."
        private const val PRIVATE_REPO_HINT = " Private repo? Add an auth block with a token to the extends entry."
        private const val HTTP_NOT_FOUND = 404
        private const val LOCALHOST_HINT = " Pass --allow-localhost or set allow-localhost: true to permit it."
        private const val PRIVATE_NETWORK_HINT = " Set allow-private-network: true to permit it."

        /** GitHub contents API (`/repos/<o>/<r>/contents/<path>`) returns the file itself, not JSON metadata. */
        private const val GITHUB_RAW_MEDIA_TYPE = "application/vnd.github.raw"

        /**
         * Throws [ConfigException] if [auth] is non-null and [url] is not HTTPS.
         */
        private fun requireHttpsForAuth(
            url: String,
            auth: HttpAuth?,
            errorCode: String
        ) {
            if (auth != null && !url.startsWith(HTTPS_PREFIX)) {
                throw ConfigException(
                    "$errorCode Authenticated request requires HTTPS. " +
                        "Refusing to send credentials over plain HTTP: $url"
                )
            }
        }
    }

    /**
     * HTTP client created lazily to avoid initialising the platform HTTP engine
     * (e.g. Curl on Linux native) when only local files are being read.
     *
     * Set to null by [close] so that any use-after-close is caught immediately
     * rather than silently reusing a closed client.
     */
    private var lazyHttpClient: Lazy<HttpClient>? = lazy {
        // Security: redirects are never followed, so auth headers never reach a redirect target
        explicitClient?.config {
            install(HttpTimeout) {
                requestTimeoutMillis = fetchTimeoutMs
                connectTimeoutMillis = fetchTimeoutMs
            }
            followRedirects = false
        } ?: ProviderHttpClientFactory.create(
            timeout = KarandashDuration.ofMillis(fetchTimeoutMs),
            followRedirects = false,
            allowLocalhost = allowLocalhost,
        )
    }
    private val httpClient: HttpClient
        get() = checkNotNull(lazyHttpClient) {
            "RemoteConfigFetcher has been closed and cannot be reused"
        }.value

    /**
     * Fetches configuration content from a URL or local file path.
     *
     * @param source The config source containing URL and optional authentication
     * @param projectRoots Directories a local file may sit under besides the working directory and the temp
     *   directory (see [localProjectRoots]); ignored for HTTP(S) sources
     * @return The content of the configuration file as a string
     * @throws ConfigException if the fetch fails, the file doesn't exist, or path traversal is detected
     */
    suspend fun fetch(
        source: ConfigSource,
        projectRoots: List<Path> = emptyList(),
    ): String =
        if (source.url.startsWith("http://") || source.url.startsWith(HTTPS_PREFIX)) {
            fetchHttp(source.url, source.auth, source.emptyAuthVars)
        } else {
            if (source.auth != null) {
                throw ConfigException(
                    "${KarandashError.REMOTE_CONFIG_FETCH_FAILED.bracketCode} Authentication is not supported for local file extends: ${source.url}"
                )
            }
            fetchLocal(source.url, projectRoots)
        }

    /**
     * Fetches content from an HTTP(S) URL with ETag support for conditional requests.
     *
     * Returns status code, content, and caching headers (ETag, Last-Modified).
     * Supports HTTP 304 Not Modified responses for cache validation.
     *
     * @param url The HTTP(S) URL to fetch
     * @param etag Optional ETag value for conditional request (If-None-Match header)
     * @param auth Optional authentication credentials
     * @param emptyAuthVars Env vars whose blank value emptied [auth], named in a 401/403/404 hint
     * @return FetchResult with status code, content, and caching headers
     * @throws ConfigException if the request fails or returns a non-success/non-304 status
     */
    suspend fun fetchWithETag(
        url: String,
        etag: String?,
        auth: HttpAuth?,
        emptyAuthVars: List<String> = emptyList(),
    ): FetchResult {
        val errorCode = KarandashError.REMOTE_CONFIG_FETCH_FAILED.bracketCode
        requireHttpsForAuth(url, auth, KarandashError.CONFIG_FETCH_AUTH_FAILED.bracketCode)
        checkUrlPolicy(url, auth, errorCode)
        return execute(url, auth, errorCode, "config", etag, emptyAuthVars) { response ->
            val notModified = response.status.value == HTTP_NOT_MODIFIED
            FetchResult(
                statusCode = response.status.value,
                content = if (notModified) "" else response.boundedText(url, errorCode),
                etag = response.headers["ETag"],
                lastModified = response.headers["Last-Modified"]
            )
        }
    }

    /**
     * Fetches content from an HTTP(S) URL.
     *
     * @param url The HTTP(S) URL to fetch
     * @param auth Optional authentication credentials
     * @return The response body as text
     * @throws ConfigException if the request fails or returns a non-success status
     */
    private suspend fun fetchHttp(
        url: String,
        auth: HttpAuth?,
        emptyAuthVars: List<String>,
    ): String {
        requireHttpsForAuth(url, auth, KarandashError.CONFIG_FETCH_AUTH_FAILED.bracketCode)
        return fetchHttpInternal(url, auth, KarandashError.REMOTE_CONFIG_FETCH_FAILED.bracketCode, "config", emptyAuthVars)
    }

    /**
     * Fetches file content from a remote URL for `{ file: "..." }` resolution.
     *
     * Thin wrapper around the HTTP client with the same auth, HTTPS and SSRF enforcement
     * as config fetching. Uses error code E0023 (external value resolution) for consistency.
     *
     * @param url The HTTP(S) URL to fetch file content from.
     * @param auth Optional authentication credentials inherited from the parent extends entry.
     * @return The response body as text.
     * @throws ConfigException if the fetch fails, auth is used over plain HTTP, or a non-success status is returned.
     */
    suspend fun fetchFileContent(
        url: String,
        auth: HttpAuth?
    ): String {
        requireHttpsForAuth(url, auth, KarandashError.EXTERNAL_VALUE_RESOLVE_FAILED.bracketCode)
        return fetchHttpInternal(url, auth, KarandashError.EXTERNAL_VALUE_RESOLVE_FAILED.bracketCode, "remote file")
    }

    /**
     * Shared HTTP fetch logic used by [fetchHttp] and [fetchFileContent].
     */
    private suspend fun fetchHttpInternal(
        url: String,
        auth: HttpAuth?,
        errorCode: String,
        resourceKind: String,
        emptyAuthVars: List<String> = emptyList(),
    ): String {
        checkUrlPolicy(url, auth, errorCode)
        return execute(url, auth, errorCode, resourceKind, etag = null, emptyAuthVars) { it.boundedText(url, errorCode) }
    }

    /**
     * Streams one GET so the body is never buffered past [MAX_BODY_BYTES]. A 304 passes only
     * for conditional requests ([etag] set); any other non-2xx - including redirects - fails.
     */
    private suspend fun <T> execute(
        url: String,
        auth: HttpAuth?,
        errorCode: String,
        resourceKind: String,
        etag: String?,
        emptyAuthVars: List<String> = emptyList(),
        read: suspend (HttpResponse) -> T,
    ): T =
        try {
            httpClient
                .prepareGet(url) {
                    applyAuth(auth)
                    if (ApiUrlParser.tryParse(url) is ParsedApiUrl.GitHubContentsUrl) header(HttpHeaders.Accept, GITHUB_RAW_MEDIA_TYPE)
                    if (etag != null) header("If-None-Match", etag)
                }.execute { response ->
                    val status = response.status.value
                    logger.debug { "RemoteConfigFetcher: GET ${UrlSanitizer.sanitize(url)} -> HTTP $status (conditional=${etag != null})" }
                    if (!response.status.isSuccess() && !(etag != null && status == HTTP_NOT_MODIFIED)) {
                        throw httpFailure(url, status, auth, errorCode, resourceKind, emptyAuthVars)
                    }
                    read(response)
                }
        } catch (e: ConfigException) {
            throw e
        } catch (e: IOException) {
            throw ConfigException("$errorCode Failed to fetch $resourceKind from ${UrlSanitizer.sanitize(url)}: ${e.message}", e)
        }

    private fun httpFailure(
        url: String,
        status: Int,
        auth: HttpAuth?,
        errorCode: String,
        resourceKind: String,
        emptyAuthVars: List<String>,
    ): ConfigException {
        val authFailure = status == 401 || status == 403
        // GitLab/GitHub answer 404, not 401, for a private project fetched without credentials
        val hint = when {
            !authFailure && status != HTTP_NOT_FOUND -> ""
            emptyAuthVars.isNotEmpty() -> " Private repo? Check ${emptyAuthVars.joinToString()}: the extends auth value is empty."
            auth == null && status == HTTP_NOT_FOUND -> PRIVATE_REPO_HINT
            !authFailure -> ""
            auth != null -> AUTH_HINT
            else -> NO_AUTH_HINT
        }
        val code = if (authFailure && auth != null) KarandashError.CONFIG_FETCH_AUTH_FAILED.bracketCode else errorCode
        return ConfigException("$code Failed to fetch $resourceKind from ${UrlSanitizer.sanitize(url)}: HTTP $status.$hint")
    }

    private suspend fun HttpResponse.boundedText(
        url: String,
        errorCode: String,
    ): String {
        val tooLarge = "$errorCode Response from ${UrlSanitizer.sanitize(url)} exceeds the $MAX_BODY_BYTES-byte limit"
        if ((contentLength() ?: 0L) > MAX_BODY_BYTES) throw ConfigException(tooLarge)
        val bytes = bodyAsChannel().readRemaining(MAX_BODY_BYTES + 1).readByteArray()
        if (bytes.size > MAX_BODY_BYTES) throw ConfigException(tooLarge)
        return bytes.decodeToString()
    }

    /**
     * Applies the [ssrfMode] host policy before any request is sent, and warns once per URL about
     * private-network hosts and anonymous plain HTTP. Messages carry only the sanitized URL.
     */
    private suspend fun checkUrlPolicy(
        url: String,
        auth: HttpAuth?,
        errorCode: String,
    ) {
        val allowPrivateNetwork = NetworkPolicy.allowsPrivateNetwork()
        val shown = UrlSanitizer.sanitize(url)
        val kind = try {
            SsrfUrlValidator.classifyHost(url)
        } catch (e: IllegalArgumentException) {
            throw ConfigException("$errorCode Invalid URL $shown: ${e.message}", e)
        }
        val rejection = rejectionReason(kind, allowPrivateNetwork)
        if (rejection != null) throw ConfigException("$errorCode Refusing to fetch $shown: $rejection")
        if ((kind == SsrfHostKind.PRIVATE || kind == SsrfHostKind.DOTLESS) && !allowLocalhost && !allowPrivateNetwork) {
            warnOnce("private:$shown", "Fetching $shown from a private-network host; make sure this source is trusted.")
        }
        if (auth == null && url.startsWith("http://")) {
            warnOnce(
                "http:$shown",
                "Fetching $shown over plain HTTP without authentication: anyone on the network path can read or " +
                    "alter it, and remote config can carry scripts:/dangerfile: entries that run locally. Use https://.",
            )
        }
    }

    private fun rejectionReason(
        kind: SsrfHostKind,
        allowPrivateNetwork: Boolean,
    ): String? =
        when (kind) {
            SsrfHostKind.BLOCKED -> {
                "the host is a cloud-metadata, link-local or blocked address (SSRF protection)."
            }

            SsrfHostKind.LOOPBACK -> {
                if (allowLocalhost) null else "the host is a loopback address (SSRF protection).$LOCALHOST_HINT"
            }

            SsrfHostKind.PRIVATE, SsrfHostKind.DOTLESS -> {
                if (ssrfMode == SsrfMode.STRICT && !allowLocalhost && !allowPrivateNetwork) {
                    "the host is on a private network (SSRF protection).$PRIVATE_NETWORK_HINT"
                } else {
                    null
                }
            }

            SsrfHostKind.PUBLIC -> {
                null
            }
        }

    private fun warnOnce(
        key: String,
        message: String,
    ) {
        if (key !in warnedUrls.getAndUpdate { it + key }) logger.warn(message)
    }

    /**
     * Reads content from a local file path with path traversal protection.
     *
     * The path is normalized (`..` collapsed) first, then the resolved absolute path must remain within the
     * working directory, one of [projectRoots] or the temp directory.
     *
     * @param path The local file path to read
     * @return The file content as text
     * @throws ConfigException if path traversal is detected, the file doesn't exist, or cannot be read
     */
    private fun fetchLocal(
        path: String,
        projectRoots: List<Path>,
    ): String {
        val file = Path(PathSecurity.normalizeLexical(resolveLocalPath(path).toString()))
        val cwd = currentWorkingDirectory()
        val roots = (listOf(cwd) + projectRoots).distinct()
        val exists = file.exists()
        // On native, resolving a missing path can throw FileNotFoundException, so a missing file is checked lexically.
        val fileAbsolute = when {
            exists -> file.absolutePathOrSelf()
            file.isAbsolute -> file
            else -> Path(PathSecurity.normalizeLexical(Path(cwd, file.toString()).toString()))
        }

        val escapes = !isPathAllowed(file, fileAbsolute, roots)
        // A `..` climb out of the root is reported as traversal even when its target is missing.
        if (!exists && !(escapes && path.split('/', '\\').any { it == ".." })) {
            throw ConfigException(
                "${KarandashError.CONFIG_FILE_NOT_FOUND.bracketCode} Config file not found: $path",
                cause = null,
                hint = "Create '$path' or fix the 'extends:' entry that points to it.",
            )
        }

        if (escapes) {
            throw ConfigException(
                "${KarandashError.REMOTE_CONFIG_FETCH_FAILED.bracketCode} Config inheritance path traversal blocked: " +
                    "$path resolves outside the project root (${roots.joinToString()})",
                cause = null,
                hint = "Keep local configs under the project root: the git top-level or the directory holding .karandash/.",
            )
        }

        if (!canRead(fileAbsolute)) {
            throw ConfigException("${KarandashError.REMOTE_CONFIG_FETCH_FAILED.bracketCode} Config file cannot be read: $path")
        }

        return try {
            fileAbsolute.readText()
        } catch (e: IOException) {
            throw ConfigException("${KarandashError.REMOTE_CONFIG_FETCH_FAILED.bracketCode} Failed to read config file $path: ${e.message}")
        }
    }

    /**
     * Checks whether [file] is located under one of [roots] or the system temp directory.
     *
     * Compares both resolved (canonical) and raw paths to handle platforms where
     * [absolutePathOrSelf] produces inconsistent results between the file and directory
     * (e.g. native Linux in containers where resolve succeeds for one but falls back for the other).
     */
    private fun isPathAllowed(
        file: Path,
        fileAbsolute: Path,
        roots: List<Path>,
    ): Boolean =
        (roots + tempDirectory()).any { root ->
            isUnder(fileAbsolute, root.absolutePathOrSelf()) || isUnder(file, root)
        }

    private fun isUnder(
        file: Path,
        base: Path,
    ): Boolean {
        val filePath = PathSecurity.normalizeLexical(file.toString())
        // A blank base would trim to "" and let every absolute path through.
        if (base.toString().isBlank()) return false
        val basePath = PathSecurity.normalizeLexical(base.toString()).trimEnd('/')
        return filePath == basePath || filePath.startsWith("$basePath/")
    }

    private fun resolveLocalPath(path: String): Path =
        if (isAbsolutePath(path)) {
            Path(path)
        } else {
            Path(currentWorkingDirectory(), path)
        }

    /**
     * This fetcher, or one like it that also allows loopback hosts when [allowLocalhost] and uses
     * [fetchTimeoutMs] (null keeps this one's): an `extends:` step fetches under the settings inherited so far.
     * Derived fetchers are reused per setting and closed by [close].
     */
    fun withSettings(
        allowLocalhost: Boolean,
        fetchTimeoutMs: Long?,
    ): RemoteConfigFetcher {
        val key = (this.allowLocalhost || allowLocalhost) to (fetchTimeoutMs ?: this.fetchTimeoutMs)
        if (key == this.allowLocalhost to this.fetchTimeoutMs) return this
        return derived
            .updateAndGet { fetchers ->
                val known = key in fetchers
                if (known) {
                    fetchers
                } else {
                    fetchers + (key to RemoteConfigFetcher(explicitClient, ssrfMode, key.first, logger, key.second, warnedUrls))
                }
            }.getValue(key)
    }

    /**
     * Closes the HTTP client and releases resources.
     *
     * After this call the fetcher must not be reused - any further call to [fetch],
     * [fetchWithETag], or [fetchFileContent] will throw [IllegalStateException].
     * The lazy delegate is nulled out so re-use after close is caught immediately
     * rather than silently operating on a closed client.
     */
    fun close() {
        derived.getAndUpdate { emptyMap() }.values.forEach { it.close() }
        val lazy = lazyHttpClient
        lazyHttpClient = null
        if (lazy != null && lazy.isInitialized()) {
            lazy.value.close()
        }
    }
}
