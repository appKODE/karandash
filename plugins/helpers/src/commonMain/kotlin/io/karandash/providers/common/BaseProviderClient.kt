package io.karandash.providers.common

import io.karandash.api.exception.ProviderApiException
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.RateLimitInfo
import io.karandash.cache.cas.ConditionalResponse
import io.karandash.http.HostCredentials
import io.karandash.http.HttpAuth
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.http.ProviderResponseCache
import io.karandash.models.support.KarandashJson
import io.karandash.pagination.PaginationResult
import io.karandash.pagination.paginateByUrl
import io.karandash.pagination.parseLinkHeaderNext
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.atomicfu.atomic
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.io.decodeFromSource

/**
 * Abstract base class for provider HTTP clients.
 *
 * Consolidates shared patterns across provider clients:
 * - Base URL validation via [UrlValidator]
 * - HTTP client creation via [ProviderHttpClientFactory]
 * - Shared [Json] instance ([KarandashJson.Default])
 * - ETag-based response caching via [ProviderResponseCache]
 * - Host-scoped auth: [credential] goes only to [baseUrl]'s host, never to another host a URL or redirect names
 *
 * Subclasses should use [trackedRequest] for all HTTP calls, or
 * [streamGetAndDecode] for large responses that benefit from streaming
 * deserialization.
 *
 * Subclasses must implement [platformName] and [credential].
 *
 * @param httpClient null builds one via [ProviderHttpClientFactory]; an injected one must also come from it: the
 *   private-network check that `baseUrl` relies on (`allowPrivateNetwork = true` at construction)
 *   runs in its ssrfRedirectValidationPlugin, so an arbitrary client bypasses SSRF.
 * @param engine engine of the client built when [httpClient] is null (a test MockEngine); null is the platform engine.
 * @param additionalCredentialHosts base URLs of other hosts this client signs requests to itself (AWS STS): they get no
 *   [credential], but the client's own credential headers on them are kept instead of stripped.
 */
abstract class BaseProviderClient(
    baseUrl: String,
    httpClient: HttpClient?,
    allowLocalhost: Boolean = false,
    protected val logger: Logger = NoOpLogger,
    private val responseCache: ProviderResponseCache? = null,
    engine: HttpClientEngine? = null,
    additionalCredentialHosts: Set<String> = emptySet(),
) {
    // private hosts are checked per request against the run's NetworkPolicy (ssrfRedirectValidationPlugin)
    val baseUrl: String = UrlValidator.validateBaseUrl(baseUrl, allowLocalhost = allowLocalhost, allowPrivateNetwork = true)
    protected val json: Json = KarandashJson.Default

    /** [credential] keyed for [baseUrl]'s host: the only host it is sent to. */
    protected val credentials: HostCredentials =
        additionalCredentialHosts.fold(HostCredentials.of(this.baseUrl, credentialHeaderNames) { credential() }) { all, host ->
            HostCredentials.of(host, credentialHeaderNames) { null } + all
        }

    private val ownsClient: Boolean = httpClient == null
    private val managedClientRef = atomic(
        httpClient
            ?: ProviderHttpClientFactory.create(
                engine = engine,
                logger = logger,
                allowLocalhost = allowLocalhost,
                credentials = credentials,
            ),
    )

    /**
     * The client this one sends through, its credential scoped to [baseUrl]'s host. Another client of the same host
     * and credential may send through it too; it stays open until this client's [close].
     */
    val sharedHttpClient: HttpClient get() = managedClientRef.value

    /** Platform name used as cache key prefix (e.g. "GitLab", "GitHub"). */
    protected abstract val platformName: String

    /** This client's credential, read at send time; null sends requests unauthenticated. */
    protected abstract fun credential(): HttpAuth?

    /**
     * Every header [credential] may set, stripped from requests to other hosts. Read during construction, so an
     * override must return constants, never a constructor property. Default: `Authorization`.
     */
    protected open val credentialHeaderNames: Set<String> get() = setOf(HttpHeaders.Authorization)

    /** Non-secret headers every request carries (API version, accept type). Default: none. */
    protected open fun HttpRequestBuilder.configureHeaders() = Unit

    /** Sets [configureHeaders] and, only when this request goes to [baseUrl]'s host, the [credential]. */
    protected fun HttpRequestBuilder.configureAuth() {
        configureHeaders()
        credentials.authFor(url.build())?.applyTo(this)
    }

    /**
     * Execute an HTTP request via the managed [HttpClient].
     *
     * All subclass HTTP calls should go through this wrapper:
     * ```
     * val response = trackedRequest { post(url) { configureAuth(); setBody(data) } }
     * ```
     */
    protected suspend fun <T> trackedRequest(block: suspend HttpClient.() -> T): T {
        prepareAuth()
        val first = managedClientRef.value.block()
        val result = if (first is HttpResponse && first.status.value == HTTP_UNAUTHORIZED && onUnauthorized()) {
            prepareAuth()
            managedClientRef.value.block()
        } else {
            first
        }
        if (result is HttpResponse) captureRateLimit(result)
        return result
    }

    /**
     * Runs before every request made through [trackedRequest] or [streamGetRawBytes], so a client whose
     * credential is fetched or refreshed over HTTP (a GitHub App installation token) can have it ready for the
     * non-suspending [configureAuth]. Default: nothing to prepare.
     */
    protected open suspend fun prepareAuth() = Unit

    /**
     * Called when a [trackedRequest] got 401. Return `true` after dropping a cached credential to have the request
     * sent once more with a fresh one. Default: `false`, the 401 stands.
     */
    protected open suspend fun onUnauthorized(): Boolean = false

    /** Sends [block] on the managed client without [prepareAuth] or the 401 retry: for fetching the credential itself. */
    protected suspend fun <T> untrackedRequest(block: suspend HttpClient.() -> T): T = managedClientRef.value.block()

    private val rateLimitRef = atomic<RateLimitInfo?>(null)

    /**
     * Latest rate-limit state seen in `X-RateLimit-*` / `RateLimit-*` response headers
     * (limit, remaining, reset as epoch seconds), or null if the server never sent a full set.
     */
    fun lastRateLimit(): RateLimitInfo? = rateLimitRef.value

    private fun captureRateLimit(response: HttpResponse) {
        rateLimitOf(response)?.let { rateLimitRef.value = it }
    }

    /**
     * GETs [url] with the configured auth: `true` on 2xx, `false` on 401/403 (credentials rejected).
     *
     * @throws ProviderApiException on any other status.
     */
    suspend fun checkAuth(url: String): Boolean {
        val response = trackedRequest { get(url) { configureAuth() } }
        val status = response.status.value
        if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) return false
        requireSuccess(response, getLabel(url))
        return true
    }

    /**
     * Sends [body] as JSON to [url] with [method] (auth as for every request) and returns the 2xx response body: for a
     * one-off write that needs no typed client method, such as a result status. Path segments in [url] must be encoded.
     *
     * @throws ProviderApiException on any other status.
     */
    suspend fun sendJson(
        method: HttpMethod,
        url: String,
        body: JsonElement,
    ): String {
        val response = trackedRequest {
            request(url) {
                this.method = method
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        return requireSuccess(response, "${method.value} $url").bodyAsText()
    }

    /**
     * Returns [response] when it is 2xx.
     *
     * @param errorBody shapes the response body quoted in the error; default: its first [MAX_ERROR_BODY_LENGTH] chars.
     * @throws ProviderApiException on any other status.
     */
    protected suspend fun requireSuccess(
        response: HttpResponse,
        requestLabel: String,
        errorBody: (String) -> String = { it.take(MAX_ERROR_BODY_LENGTH) },
    ): HttpResponse {
        if (response.status.isSuccess()) return response
        throw ProviderApiException(
            statusCode = response.status.value,
            statusDescription = response.status.description,
            platformName = platformName,
            requestLabel = requestLabel,
            responseBody = errorBody(response.bodyAsText()),
        )
    }

    /**
     * GETs [url]'s body as text, or null on 404.
     *
     * @throws ProviderApiException on any other non-2xx status.
     */
    protected suspend fun getTextOrNull(
        url: String,
        requestLabel: String = getLabel(url),
    ): String? {
        val response = trackedRequest { get(url) { configureAuth() } }
        if (response.status.value == HTTP_NOT_FOUND) return null
        return requireSuccess(response, requestLabel).bodyAsText()
    }

    /** The request label of a GET of [url] in errors and logs. */
    protected fun getLabel(url: String): String = "GET $url"

    /** GETs [url]'s body decoded as [T] ([decodeBody]), or null on 404; other failures as [getTextOrNull]. */
    protected suspend inline fun <reified T> getOrNull(
        url: String,
        requestLabel: String = getLabel(url),
    ): T? = getTextOrNull(url, requestLabel)?.let { decodeBody<T>(it, requestLabel) }

    /** Close the managed HTTP client. */
    suspend fun close() {
        if (!ownsClient) return
        managedClientRef.value.close()
    }

    /** The injected response cache, else the calling run's ([ProviderHttpClientFactory.RESPONSE_CACHE]). */
    protected suspend fun activeResponseCache(): ProviderResponseCache? = responseCache ?: ProviderHttpClientFactory.responseCache()

    /**
     * Perform a GET request with optional ETag-based response caching.
     *
     * When a response cache is active ([activeResponseCache]):
     * - On cache hit within TTL: returns cached response body without an HTTP call.
     * - On expired entry with ETag: sends conditional request with `If-None-Match`.
     *   On HTTP 304, refreshes TTL and returns cached body.
     * - On miss or non-304: stores the response body with its ETag for future use.
     *
     * @param url The full URL to GET.
     * @param requestLabel Human-readable label for error messages.
     * @param configure Optional additional request configuration (e.g. accept headers).
     * @return The raw response body as a string.
     * @throws ProviderApiException if the response status is not successful.
     */
    protected suspend fun cachedGetRaw(
        url: String,
        requestLabel: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): String = cachedGetPage(url, requestLabel, configure).first

    /**
     * [cachedGetRaw] that also returns the next-page URL taken from the response by [nextOf] (default: the
     * `Link: rel="next"` header), cached with the body so cached pages stay paginated.
     */
    protected suspend fun cachedGetPage(
        url: String,
        requestLabel: String,
        configure: HttpRequestBuilder.() -> Unit = {},
        nextOf: (HttpResponse) -> String? = { parseLinkHeaderNext(it.headers[HttpHeaders.Link]) },
    ): Pair<String, String?> {
        val page =
            activeResponseCache()?.getOrFetch(platformName, "GET", url) { etag -> fetchPage(url, requestLabel, etag, configure, nextOf) }
                ?: fetchPage(url, requestLabel, null, configure, nextOf)
        return page.content to page.nextLink
    }

    /** One GET for [cachedGetPage]: 304 to a conditional request, otherwise 200 with body, ETag and next link. */
    private suspend fun fetchPage(
        url: String,
        requestLabel: String,
        etag: String?,
        configure: HttpRequestBuilder.() -> Unit,
        nextOf: (HttpResponse) -> String?,
    ): ConditionalResponse {
        val response = trackedRequest {
            get(url) {
                configureAuth()
                if (etag != null) header(HttpHeaders.IfNoneMatch, etag)
                configure()
            }
        }
        if (etag != null && response.status.value == HTTP_NOT_MODIFIED) return ConditionalResponse(HTTP_NOT_MODIFIED, "")
        val rawBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw ProviderApiException(
                statusCode = response.status.value,
                statusDescription = response.status.description,
                platformName = platformName,
                requestLabel = requestLabel,
                responseBody = rawBody.take(MAX_ERROR_BODY_LENGTH),
            )
        }
        return ConditionalResponse(HttpStatusCode.OK.value, rawBody, response.headers[HttpHeaders.ETag], nextOf(response))
    }

    /**
     * Deserialize a raw JSON body, wrapping parse errors in a readable message.
     *
     * @param rawBody The raw JSON string.
     * @param requestLabel Human-readable label for error context.
     * @return The deserialized object.
     * @throws IllegalStateException if parsing fails.
     */
    protected inline fun <reified T> decodeBody(
        rawBody: String,
        requestLabel: String,
    ): T =
        try {
            json.decodeFromString(rawBody)
        } catch (e: SerializationException) {
            throw IllegalStateException(
                "$platformName API response parsing failed ($requestLabel): ${e.message}. " +
                    "Response: ${rawBody.take(MAX_ERROR_BODY_LENGTH)}",
                e,
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(
                "$platformName API response parsing failed ($requestLabel): ${e.message}. " +
                    "Response: ${rawBody.take(MAX_ERROR_BODY_LENGTH)}",
                e,
            )
        }

    /**
     * Convenience: cached GET + deserialization in one call.
     *
     * @param url The full URL to GET.
     * @param requestLabel Human-readable label for error messages.
     * @param configure Optional additional request configuration.
     * @return The deserialized response.
     */
    protected suspend inline fun <reified T> cachedGetAndDecode(
        url: String,
        requestLabel: String,
        noinline configure: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val raw = cachedGetRaw(url, requestLabel, configure)
        return decodeBody(raw, requestLabel)
    }

    /**
     * Streaming GET + deserialization: reads the HTTP response as raw bytes and
     * deserializes via [kotlinx.serialization.json.io.decodeFromSource], avoiding
     * the intermediate String allocation that [cachedGetAndDecode] requires via
     * [bodyAsText]. Reduces memory pressure for large responses (2MB+).
     *
     * Cache hits still use String-based decoding. For stale ETag entries,
     * falls back to [cachedGetAndDecode] (conditional request).
     *
     * @param url The full URL to GET.
     * @param requestLabel Human-readable label for error messages.
     * @param configure Optional additional request configuration.
     * @return The deserialized response.
     */
    protected suspend inline fun <reified T> streamGetAndDecode(
        url: String,
        requestLabel: String,
        noinline configure: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val cache = activeResponseCache()
        if (cache != null) {
            val lookup = cache.get(platformName, "GET", url)
            val hit = lookup.hit
            if (hit != null) return decodeBody(hit.responseBody, requestLabel)
            if (lookup.staleEtag != null) return cachedGetAndDecode(url, requestLabel, configure)
        }
        val bytes = streamGetRawBytes(url, requestLabel, configure)
        val source = Buffer().also { it.write(bytes) }
        return try {
            json.decodeFromSource<T>(source)
        } catch (e: SerializationException) {
            throw IllegalStateException(
                "$platformName API streaming parse failed ($requestLabel): ${e.message}",
                e,
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(
                "$platformName API streaming parse failed ($requestLabel): ${e.message}",
                e,
            )
        }
    }

    /**
     * Perform a GET request and return the raw response bytes without String conversion.
     */
    protected suspend fun streamGetRawBytes(
        url: String,
        requestLabel: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): ByteArray {
        prepareAuth()
        val client = managedClientRef.value
        return client
            .prepareGet(url) {
                configureAuth()
                configure()
            }.execute { httpResponse ->
                captureRateLimit(httpResponse)
                if (!httpResponse.status.isSuccess()) {
                    val errorBody = httpResponse.bodyAsText()
                    throw ProviderApiException(
                        statusCode = httpResponse.status.value,
                        statusDescription = httpResponse.status.description,
                        platformName = platformName,
                        requestLabel = requestLabel,
                        responseBody = errorBody.take(MAX_ERROR_BODY_LENGTH),
                    )
                }
                httpResponse.body<ByteArray>()
            }
    }

    /**
     * GETs every page of a JSON-array endpoint and decodes them into one list. Pages go through the ETag
     * response cache like [cachedGetRaw]. Starts at [firstUrl] (which
     * should carry the page-size parameter) and follows the `Link: <…>; rel="next"` header, which GitHub,
     * GitLab and Gitea all send. Stops after [cap] pages (null: [maxPages]) with a warning that later items are missing.
     *
     * @throws ProviderApiException on a non-2xx page.
     */
    protected suspend inline fun <reified T> getAllPages(
        firstUrl: String,
        requestLabel: String,
        cap: Int? = null,
    ): List<T> = getAllPagesRaw(firstUrl, requestLabel, cap).flatMap { decodeBody<List<T>>(it, requestLabel) }

    /** Raw page bodies for [getAllPages]. */
    protected suspend fun getAllPagesRaw(
        firstUrl: String,
        requestLabel: String,
        cap: Int? = null,
    ): List<String> =
        paginateByUrl(firstUrl, cap ?: maxPages(), warnTruncated("$platformName $requestLabel")) { url ->
            val (rawBody, next) = cachedGetPage(url, requestLabel)
            PaginationResult(listOf(rawBody), next)
        }

    /**
     * Text of the repository file whose body [url] serves, or null when it is missing (404), over
     * [PlatformServiceModule.MAX_FILE_CONTENT_BYTES] or binary ([fileText]). A `Content-Length` over [bodyLimit]
     * skips the body unread, otherwise reading stops one byte past it. [decode] unwraps a body the platform encodes
     * (Gerrit sends base64, with a [bodyLimit] of 4/3 the cap).
     */
    protected suspend fun getFileTextOrNull(
        url: String,
        requestLabel: String,
        bodyLimit: Long = PlatformServiceModule.MAX_FILE_CONTENT_BYTES.toLong(),
        decode: (ByteArray) -> ByteArray? = { it },
        configure: HttpRequestBuilder.() -> Unit = {},
    ): String? {
        val bytes = trackedRequest {
            prepareGet(url) {
                configureAuth()
                configure()
            }.execute { response ->
                if (response.status.value == HTTP_NOT_FOUND) {
                    // The curl engine (native) holds an unread body until the request timeout, ~60s per missing file.
                    response.bodyAsText()
                    return@execute null
                }
                requireSuccess(response, requestLabel)
                val declared = response.contentLength()
                val body = if (declared != null &&
                    declared > bodyLimit
                ) {
                    null
                } else {
                    response.bodyAsChannel().readRemaining(bodyLimit + 1).readByteArray()
                }
                if (body == null || body.size > bodyLimit) {
                    logger.warn("Skipping $requestLabel: over the ${PlatformServiceModule.MAX_FILE_CONTENT_BYTES}-byte cap")
                    null
                } else {
                    decode(body)
                }
            }
        } ?: return null
        return fileText(bytes, requestLabel)
    }

    /**
     * [bytes] as text, or null when over [PlatformServiceModule.MAX_FILE_CONTENT_BYTES] (warned) or binary: a NUL
     * byte in the first [BINARY_SNIFF_BYTES].
     */
    protected fun fileText(
        bytes: ByteArray,
        requestLabel: String,
    ): String? {
        val cap = PlatformServiceModule.MAX_FILE_CONTENT_BYTES
        return when {
            bytes.size > cap -> null.also { logger.warn("Skipping $requestLabel: over the $cap-byte cap") }
            bytes.copyOf(minOf(bytes.size, BINARY_SNIFF_BYTES)).contains(0) -> null.also { logger.debug("Skipping $requestLabel: binary") }
            else -> bytes.decodeToString()
        }
    }

    /** Page-cap callback for the pagination helpers: warns that [subject] (platform and request) lost its later items. */
    protected fun warnTruncated(subject: String): (Int) -> Unit =
        { cap -> logger.warn("$subject: stopped after $cap pages (http.pagination.maxPages); later items are missing") }

    /** Pagination safety cap: the calling run's `http.pagination.maxPages` when configured, else [default]. */
    protected suspend fun maxPages(default: Int = MAX_PAGES): Int = ProviderHttpClientFactory.maxPages(default)

    protected companion object {
        const val HTTP_NOT_MODIFIED = 304
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404

        /** Bytes [fileText] scans for a NUL to tell binary from text. */
        const val BINARY_SNIFF_BYTES = 8 * 1024
        const val MAX_ERROR_BODY_LENGTH = 500

        /** Maximum items per page for paginated API requests. */
        const val MAX_PER_PAGE = 100

        /** Default safety cap on total pages fetched; `http.pagination.maxPages` overrides it. */
        const val MAX_PAGES = 10
    }
}

/** The rate-limit state in [response]'s `X-RateLimit-*` / `RateLimit-*` headers, or null without a full set. */
private fun rateLimitOf(response: HttpResponse): RateLimitInfo? {
    fun header(name: String): Long? = (response.headers["X-RateLimit-$name"] ?: response.headers["RateLimit-$name"])?.trim()?.toLongOrNull()
    val remaining = header("Remaining") ?: return null
    val limit = header("Limit") ?: return null
    val reset = header("Reset") ?: return null
    return RateLimitInfo(remaining.toInt(), limit.toInt(), reset)
}
