package io.karandash.http

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.io.IOException

/**
 * Installed on a client that skips certificate verification: refuses every hop (redirects too) to a host outside
 * [insecureHosts], so unverified TLS never reaches a host the user did not list.
 */
internal fun tlsInsecureHostGuard(insecureHosts: Set<String>) =
    createClientPlugin("TlsInsecureHostGuard") {
        on(Send) { request ->
            val host = request.url.host.lowercase()
            if (host !in insecureHosts) {
                throw IOException("Refusing unverified HTTPS to $host: not listed in $TLS_INSECURE_HOSTS_ENV")
            }
            proceed(request)
        }
    }
