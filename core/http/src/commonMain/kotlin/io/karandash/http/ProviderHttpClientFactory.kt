package io.karandash.http

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.exception.ProviderApiException
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.ServiceKey
import io.karandash.api.run.serviceKey
import io.karandash.config.KarandashLimits
import io.karandash.duration.KarandashDuration
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.support.KarandashJson
import io.karandash.platform.PlatformEnvironment
import io.karandash.security.UrlSanitizer
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestRetryConfig
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.json.Json

/**
 * Shared factory for creating Ktor HTTP clients with consistent defaults.
 * Always use this factory - never create Ktor clients directly.
 */
object ProviderHttpClientFactory {
    private val defaultJson = KarandashJson.Lenient

    /** Run-scoped provider response cache, read by provider clients per call through [responseCache]. */
    @ExperimentalKarandashApi
    val RESPONSE_CACHE: ServiceKey<ProviderResponseCache> = serviceKey("http.responseCache")

    /**
     * Run-scoped diagnostic listener: fixed at creation for a client built with run services, else read per request
     * from the calling run by [HttpDiagnosticPlugin].
     */
    @ExperimentalKarandashApi
    val DIAGNOSTIC_LISTENER: ServiceKey<HttpDiagnosticListener> = serviceKey("http.diagnosticListener")

    /** Run-scoped created/closed ledger for every client [create] builds. */
    @ExperimentalKarandashApi
    val CLIENT_LEDGER: ServiceKey<HttpClientLedger> = serviceKey("http.clientLedger")

    /**
     * Run-scoped `http:`/`timeouts:` settings. Read when a client is created with run services, else per request
     * from the calling run, so clients built in `initialize()` follow the run that uses them.
     */
    @ExperimentalKarandashApi
    val HTTP_SETTINGS: ServiceKey<HttpClientSettings> = serviceKey("http.settings")

    @OptIn(ExperimentalKarandashApi::class)
    private val fallbackLedger = atomic<HttpClientLedger?>(null)

    /** The calling run's pagination cap (`http.pagination.maxPages`), else [default] (the client's own cap). */
    @OptIn(ExperimentalKarandashApi::class)
    suspend fun maxPages(default: Int): Int = RunScopedServices.current()?.get(HTTP_SETTINGS)?.maxPages ?: default

    /**
     * Installs [ledger] as the fallback for clients created without run services. Cleared with
     * [clearLedger] by the same owner only, so a stale run cannot clear a newer run's ledger.
     */
    @ExperimentalKarandashApi
    fun installLedger(ledger: HttpClientLedger) {
        fallbackLedger.value = ledger
    }

    /** Clears the fallback ledger only if it is still [ledger]. */
    @ExperimentalKarandashApi
    fun clearLedger(ledger: HttpClientLedger) {
        fallbackLedger.compareAndSet(ledger, null)
    }

    /** The calling run's [RESPONSE_CACHE], or null outside a run or when `cache.responses.enabled` is false. */
    @OptIn(ExperimentalKarandashApi::class)
    suspend fun responseCache(): ProviderResponseCache? = RunScopedServices.current()?.get(RESPONSE_CACHE)

    private val DEFAULT_TIMEOUT: KarandashDuration = KarandashDuration.ofSeconds(KarandashLimits.DEFAULT_HTTP_TIMEOUT_SECONDS)
    private val SERVER_ERRORS = 500..599

    /** Longest `Retry-After` a 429 answer can make a retry wait; a larger value is cut to this. */
    const val MAX_RETRY_AFTER_MS = RetryBackoff.MAX_DELAY_MS

    /** Methods safe to repeat: a POST/PATCH that failed after the server acted on it must not run twice. */
    private val IDEMPOTENT_METHODS = setOf(
        HttpMethod.Get,
        HttpMethod.Head,
        HttpMethod.Put,
        HttpMethod.Delete,
        HttpMethod.Options,
    )

    /**
     * Creates an HTTP client with JSON content negotiation, timeout, and retry configuration.
     *
     * @param engine Optional engine to use (useful for testing with MockEngine). Defaults to platform engine.
     * @param timeout Request, connect, and socket timeout. Defaults to the run's `timeouts.http` (from [services], else
     *   per request from the calling run), else 60s.
     * @param connectTimeout Connect timeout override. Defaults to the resolved [timeout].
     * @param json JSON configuration. Defaults to lenient, ignore-unknown-keys, encode-defaults.
     * @param expectSuccess If true, Ktor throws on non-2xx responses.
     * @param authContext Optional provider context: a 401/403 then throws [ProviderApiException] with a sanitized URL.
     * @param logger Logger for HTTP request/response debug output. Defaults to [NoOpLogger] (no overhead).
     * @param allowLocalhost When true, skips SSRF blocked-host and private-IP checks.
     *   Required when the target URL is intentionally localhost (e.g. `allow_localhost: true` config).
     * @param services Run services; their [DIAGNOSTIC_LISTENER] and [HTTP_SETTINGS] are fixed at creation and their
     *   [CLIENT_LEDGER] wins over the fallback ledger. Without them the settings and the listener are read per request
     *   from the calling run ([runSettingsPlugin], [HttpDiagnosticPlugin]).
     * @param followRedirects False returns 3xx answers as-is, so credentials never follow a redirect.
     * @param credentials Credentials keyed by host, applied per request and per redirect hop by
     *   [hostScopedAuthPlugin]: a credential never reaches a host it is not keyed for. When every keyed host is in
     *   `KARANDASH_TLS_INSECURE_HOSTS`, the client skips certificate verification and refuses any other host.
     * @param retryUnsentPost Also retry a POST/PATCH when the server cannot have acted on it: a 429 answer or a
     *   connect timeout. Off by default, so non-idempotent requests are never repeated unless a caller opts in.
     * @param idleTimeoutOnly [timeout] bounds only the connect and the silence between two reads, not the whole call:
     *   a streamed reply that keeps sending runs as long as it needs (`ai.stream`).
     * @param socketTimeout Silence allowed between two reads; defaults to the resolved [timeout]. A shorter value fails a
     *   connection that stops sending sooner than the whole-call [timeout] would.
     * @param retry Fixed retry policy that wins over the run's `http.retry`; a caller with its own retry loop (the AI
     *   providers) passes `maxRetries = 0` so a request is never retried twice over.
     */
    @OptIn(ExperimentalKarandashApi::class)
    fun create(
        engine: HttpClientEngine? = null,
        timeout: KarandashDuration? = null,
        connectTimeout: KarandashDuration? = null,
        json: Json = defaultJson,
        expectSuccess: Boolean = false,
        authContext: String? = null,
        logger: Logger = NoOpLogger,
        allowLocalhost: Boolean = false,
        services: RunScopedServices? = null,
        followRedirects: Boolean = true,
        credentials: HostCredentials? = null,
        retryUnsentPost: Boolean = false,
        idleTimeoutOnly: Boolean = false,
        socketTimeout: KarandashDuration? = null,
        retry: HttpClientSettings.Retry? = null,
    ): HttpClient {
        val settings = services?.get(HTTP_SETTINGS)
        val requestTimeout = timeout ?: settings?.timeout ?: DEFAULT_TIMEOUT
        val insecureHosts = tlsInsecureHosts(PlatformEnvironment::getEnv)
        val insecure = engine == null && skipsTlsVerification(credentials, insecureHosts)
        if (insecure) warnTlsVerificationSkipped(insecureHosts)
        val configure: HttpClientConfig<*>.() -> Unit = {
            this.expectSuccess = expectSuccess
            this.followRedirects = followRedirects
            if (engine == null) configureDefaultEngine(insecure)
            if (insecure) install(tlsInsecureHostGuard(insecureHosts))
            // first: HttpSend runs interceptors in install order, and HttpTimeout/HttpRequestRetry read what it sets
            if (services == null) {
                install(
                    runSettingsPlugin(explicitTimeout = timeout != null, connectTimeout = connectTimeout, explicitRetry = retry != null),
                )
            }
            HttpResponseValidator {
                handleResponseExceptionWithRequest { cause, request ->
                    if (cause.isEngineTransportFailure()) {
                        throw IOException("Request to ${request.url.host} failed: ${cause.message}", cause)
                    }
                }
            }
            install(
                ssrfRedirectValidationPlugin(
                    allowLocalhost = allowLocalhost,
                    resolve = if (engine ==
                        null
                    ) {
                        ::resolveHostAddresses
                    } else {
                        null
                    }
                )
            )
            if (credentials != null) install(hostScopedAuthPlugin(credentials))
            if (retryUnsentPost) install(RetryUnsentPostPlugin)
            installRetry(retry ?: settings?.retry)
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = if (idleTimeoutOnly) HttpTimeoutConfig.INFINITE_TIMEOUT_MS else requestTimeout.millis
                connectTimeoutMillis = (connectTimeout ?: requestTimeout).millis
                socketTimeoutMillis = (socketTimeout ?: requestTimeout).millis
            }
            if (logger !== NoOpLogger) {
                install(HttpLoggingPlugin) {
                    this.logger = logger
                }
            }
            val diagListener = services?.get(DIAGNOSTIC_LISTENER)
            if (services == null || diagListener != null) {
                install(HttpDiagnosticPlugin) {
                    this.listener = diagListener
                }
            }
            if (authContext != null) {
                HttpResponseValidator {
                    handleResponseExceptionWithRequest { cause, request ->
                        val responseException = cause as? ResponseException ?: return@handleResponseExceptionWithRequest
                        val status = responseException.response.status
                        if (status != HttpStatusCode.Unauthorized && status != HttpStatusCode.Forbidden) {
                            return@handleResponseExceptionWithRequest
                        }

                        val reason =
                            if (status == HttpStatusCode.Unauthorized) {
                                "Authentication failed"
                            } else {
                                "Access denied"
                            }
                        // no cause: the Ktor exception's message carries the raw URL
                        val label = "${request.method.value} ${UrlSanitizer.sanitize(request.url.toString())}"
                        throw ProviderApiException(
                            statusCode = status.value,
                            statusDescription = status.description,
                            platformName = authContext,
                            requestLabel = label,
                            responseBody = "",
                            message = "$reason for $authContext: HTTP ${status.value} ${status.description} while calling $label",
                        )
                    }
                }
            }
        }
        val client = if (engine != null) HttpClient(engine, configure) else HttpClient(configure)
        (services?.get(CLIENT_LEDGER) ?: fallbackLedger.value)?.onCreate(client)
        return client
    }

    /**
     * [retry], or [HttpClientSettings.Retry.DEFAULT] when `http.retry` is unset; [runSettingsPlugin] replaces it
     * per request with the run's own `http.retry`.
     */
    private fun HttpClientConfig<*>.installRetry(retry: HttpClientSettings.Retry?) {
        install(HttpRequestRetry) { applyRetry(retry ?: HttpClientSettings.Retry.DEFAULT) }
    }

    /**
     * Decision C, idempotent 5xx and 429 only, exponential backoff from [retry]'s base delay; a 429 waits
     * at least its `Retry-After` seconds, capped at [MAX_RETRY_AFTER_MS]. A request marked [RETRY_UNSENT_POST] is
     * also retried on a 429 or a connect timeout, whatever its method.
     */
    internal fun HttpRequestRetryConfig.applyRetry(retry: HttpClientSettings.Retry) {
        retryIf(retry.maxRetries) { request, response ->
            val tooMany = response.status == HttpStatusCode.TooManyRequests
            (request.method in IDEMPOTENT_METHODS && (response.status.value in SERVER_ERRORS || tooMany)) ||
                (tooMany && RETRY_UNSENT_POST in request.attributes)
        }
        // A POST/PATCH that failed in transit may have reached the server; a timeout already waited its full budget.
        // limit: only a connect timeout counts as unsent; a refused connection has no common Ktor type
        retryOnExceptionIf { request, cause ->
            cause !is CancellationException &&
                (
                    (request.method in IDEMPOTENT_METHODS && !cause.isTimeout()) ||
                        (cause is ConnectTimeoutException && RETRY_UNSENT_POST in request.attributes)
                )
        }
        delayMillis(respectRetryAfterHeader = false) { attempt ->
            RetryBackoff.delayMs(attempt, retry.baseDelay.millis, response?.headers?.let(RetryBackoff::retryAfterMs))
        }
    }

    /** Marks a request that [applyRetry] may repeat on a 429 or a connect timeout even when it is a POST. */
    private val RETRY_UNSENT_POST = AttributeKey<Unit>("karandash.retryUnsentPost")

    private val RetryUnsentPostPlugin = createClientPlugin("RetryUnsentPost") {
        onRequest { request, _ -> request.attributes.put(RETRY_UNSENT_POST, Unit) }
    }

    private fun Throwable.isTimeout(): Boolean =
        this is HttpRequestTimeoutException || this is ConnectTimeoutException || this is SocketTimeoutException

    /**
     * Creates a provider-focused HTTP client that fails fast on non-2xx responses and
     * surfaces 401/403 as consistent authentication errors.
     *
     * @param logger Logger for HTTP request/response debug output. Defaults to [NoOpLogger] (no overhead).
     * @param allowLocalhost When true, skips SSRF blocked-host and private-IP checks (`allow_localhost: true`).
     * @param credentials Credentials keyed by host; see [create].
     */
    fun createProvider(
        providerName: String,
        engine: HttpClientEngine? = null,
        timeout: KarandashDuration? = null,
        connectTimeout: KarandashDuration? = null,
        json: Json = defaultJson,
        logger: Logger = NoOpLogger,
        allowLocalhost: Boolean = false,
        credentials: HostCredentials? = null,
    ): HttpClient =
        create(
            engine = engine,
            timeout = timeout,
            connectTimeout = connectTimeout,
            json = json,
            expectSuccess = true,
            authContext = "$providerName API",
            logger = logger,
            allowLocalhost = allowLocalhost,
            credentials = credentials,
        )
}
