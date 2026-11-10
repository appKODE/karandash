package io.karandash.http

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.run.RunScopedServices
import io.karandash.security.UrlSanitizer
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import kotlin.time.TimeSource

/**
 * Ktor client plugin that feeds HTTP request metrics to an [HttpDiagnosticListener].
 *
 * Captures method, URL, status code, duration, and response size for every request.
 * Installed by [ProviderHttpClientFactory]: with the listener of the run services the client was created with, else
 * with none, and then each request reports to the calling run's [ProviderHttpClientFactory.DIAGNOSTIC_LISTENER], so
 * two runs sharing a client never see each other's requests.
 * Independent of [HttpLoggingPlugin] - both can be active simultaneously.
 *
 * Never captures headers, request bodies or the query string, and runs the URL and error through [UrlSanitizer], to
 * prevent leaking secrets.
 */
@OptIn(ExperimentalKarandashApi::class)
internal val HttpDiagnosticPlugin = createClientPlugin("HttpDiagnostic", ::HttpDiagnosticConfig) {
    val fixedListener = pluginConfig.listener

    on(Send) { request ->
        val listener = fixedListener
            ?: RunScopedServices.current()?.get(ProviderHttpClientFactory.DIAGNOSTIC_LISTENER)
            ?: return@on proceed(request)
        val method = request.method.value
        val url = UrlSanitizer.sanitize(
            request.url
                .buildString()
                .substringBefore('?')
                .substringBefore('#')
        )
        val startMark = TimeSource.Monotonic.markNow()
        @Suppress("TooGenericExceptionCaught") // catch-and-rethrow: proceed() can throw varied types
        try {
            val response = proceed(request)
            val ms = startMark.elapsedNow().inWholeMilliseconds
            val status = response.response.status.value
            val size = response.response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            listener.onRequestComplete(
                method = method,
                url = url,
                statusCode = status,
                durationMs = ms,
                responseSizeBytes = size,
                error = null,
            )
            response
        } catch (e: Exception) {
            // boundary: Ktor interceptor only observes the failure and rethrows it
            val ms = startMark.elapsedNow().inWholeMilliseconds
            listener.onRequestComplete(
                method = method,
                url = url,
                statusCode = null,
                durationMs = ms,
                responseSizeBytes = null,
                error = e.message?.let(UrlSanitizer::sanitizeText)?.take(MAX_ERROR_LENGTH),
            )
            throw e
        }
    }
}

/**
 * Configuration for [HttpDiagnosticPlugin].
 */
internal class HttpDiagnosticConfig {
    /** Listener fixed at creation; null reads the calling run's listener per request. */
    var listener: HttpDiagnosticListener? = null
}

private const val MAX_ERROR_LENGTH = 200
