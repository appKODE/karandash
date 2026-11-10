package io.karandash.http

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.util.AttributeKey

private val OriginHost = AttributeKey<String>("karandash.auth.originHost")

/**
 * Applies [credentials] per host at [Send], which Ktor re-enters for every redirect hop (see
 * [ssrfRedirectValidationPlugin]). A request to a keyed host gets that host's credential unless the caller already
 * set the header (an explicit JWT or approve token). When a hop leaves the host the request started on, every
 * credential header is dropped first, then the new host's own credential (if any) is applied; a request to an
 * unkeyed host never carries a registered credential header.
 *
 * Installed by [ProviderHttpClientFactory.create] when credentials are passed.
 */
internal fun hostScopedAuthPlugin(credentials: HostCredentials) =
    createClientPlugin("HostScopedAuth") {
        on(Send) { request ->
            val host = HostCredentials.hostKey(request.url.build())
            val origin = request.attributes.getOrNull(OriginHost) ?: host.also { request.attributes.put(OriginHost, it) }
            val url = request.url.build()
            val auth = credentials.authFor(url)
            // A keyed host whose credential is not available yet (GitHub App JWT exchange, SigV4) keeps the
            // caller's own header; an unkeyed host or a cross-host hop never does.
            if (origin != host || !credentials.isKeyed(url)) {
                (credentials.headerNames() + HttpHeaders.Authorization).forEach(request.headers::remove)
            }
            if (auth != null && request.headers[auth.headerName] == null) auth.applyTo(request)
            proceed(request)
        }
    }
