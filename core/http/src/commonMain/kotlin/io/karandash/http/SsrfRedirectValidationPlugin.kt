package io.karandash.http

import io.karandash.security.UrlSanitizer
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.URLProtocol
import io.ktor.util.AttributeKey

/**
 * Creates a Ktor client plugin that validates every outgoing request URL - including HTTP
 * redirect targets - through [SsrfUrlValidator].
 *
 * In Ktor 3.x, [io.ktor.client.plugins.HttpRedirect] follows redirects by calling
 * `sender.execute(newRequest)` for each Location URL, which re-enters the full plugin
 * chain. This plugin intercepts at [Send] so that both the original request and every
 * redirect target are validated before the HTTP call is made.
 *
 * Uses [SsrfUrlValidator.validateBaseUrl] (not [SsrfUrlValidator.validate]) so that
 * legitimate public hostnames such as `api.github.com` are allowed while private IPs
 * and blocked hosts are rejected (private hosts pass when the calling run's [NetworkPolicy] allows them, read per request).
 *
 * Plain `http` is allowed only when the request chain started on `http`: the first hop records its scheme in
 * [OriginProtocol], which a redirect request inherits (Ktor copies the attributes), so an `https` origin never
 * follows a redirect to `http`.
 *
 * If a URL is blocked, [IllegalArgumentException] is thrown before the call is made.
 *
 * @param allowLocalhost When true, skips blocked-host and private-IP checks (mirrors
 *   [SsrfUrlValidator.validateBaseUrl]). Pass through from [ProviderHttpClientFactory.create].
 * @param resolve Resolves a host name to its addresses for [SsrfUrlValidator.requirePublicAddresses]; null skips
 *   that check (a caller-supplied engine, e.g. a test `MockEngine`, never reaches the network).
 *
 * Installed automatically by [ProviderHttpClientFactory.create].
 */
internal fun ssrfRedirectValidationPlugin(
    allowLocalhost: Boolean = false,
    resolve: (suspend (String) -> List<String>)? = null,
) = createClientPlugin("SsrfRedirectValidation") {
    on(Send) { request ->
        val url = request.url.buildString()
        if (isAbsoluteUrl(url)) {
            val allowPrivateNetwork = NetworkPolicy.allowsPrivateNetwork()
            val origin = request.attributes.computeIfAbsent(OriginProtocol) { request.url.protocol }
            try {
                SsrfUrlValidator.validateBaseUrl(
                    url,
                    allowHttp = origin == URLProtocol.HTTP,
                    allowLocalhost = allowLocalhost,
                    allowPrivateNetwork = allowPrivateNetwork
                )
                if (!allowLocalhost && resolve != null) {
                    SsrfUrlValidator.requirePublicAddresses(request.url.host, resolve, allowPrivateNetwork)
                }
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "SSRF protection blocked request to '${UrlSanitizer.sanitize(url)}': ${e.message?.let(UrlSanitizer::sanitizeText)}",
                    e,
                )
            }
        }
        proceed(request)
    }
}

/** The scheme of the first request of a redirect chain; redirect requests inherit it. */
internal val OriginProtocol: AttributeKey<URLProtocol> = AttributeKey("KarandashSsrfOriginProtocol")

private fun isAbsoluteUrl(url: String) = url.startsWith("https://") || url.startsWith("http://")
