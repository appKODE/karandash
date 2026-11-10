package io.karandash.http

import io.karandash.logger.Logger
import io.karandash.security.TextRedactor
import io.karandash.security.UrlSanitizer
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import kotlin.time.TimeSource

/**
 * Lightweight Ktor client plugin that logs HTTP request/response pairs via [Logger.debug].
 *
 * Logs request method and URL on send, and response status with round-trip duration on receive.
 * Never logs headers to prevent leaking auth tokens or other secrets; the URL goes through
 * [UrlSanitizer] (userinfo, sensitive query values, webhook paths) and error messages through
 * [TextRedactor.redactForLog].
 *
 * Only installed when a non-[io.karandash.sdk.NoOpLogger] logger is provided to
 * [ProviderHttpClientFactory], so there is zero overhead when verbose mode is off.
 */
internal val HttpLoggingPlugin = createClientPlugin("HttpLogging", ::HttpLoggingConfig) {
    val logger = pluginConfig.logger

    on(Send) { request ->
        val method = request.method.value
        val url = UrlSanitizer.sanitize(request.url.buildString())
        logger.debug("HTTP $method $url")
        val startMark = TimeSource.Monotonic.markNow()
        @Suppress("TooGenericExceptionCaught") // catch-and-rethrow: proceed() can throw varied types
        try {
            val response = proceed(request)
            val ms = startMark.elapsedNow().inWholeMilliseconds
            val status = response.response.status
            logger.debug("HTTP $method $url -> ${status.value} ${status.description} (${ms}ms)")
            response
        } catch (e: Exception) {
            // boundary: Ktor interceptor only observes the failure and rethrows it
            val ms = startMark.elapsedNow().inWholeMilliseconds
            logger.debug("HTTP $method $url -> ERROR (${ms}ms): ${e.message?.let(TextRedactor::redactForLog)}")
            throw e
        }
    }
}

/**
 * Configuration for [HttpLoggingPlugin].
 */
internal class HttpLoggingConfig {
    /** Logger instance to use for debug output. */
    lateinit var logger: Logger
}
