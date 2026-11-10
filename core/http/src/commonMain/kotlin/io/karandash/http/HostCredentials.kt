package io.karandash.http

import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.parseUrl

/**
 * Credentials keyed by host: the only place that decides whether an [HttpAuth] may go to a URL. A credential is
 * sent to the host (and port) of the base URL it was registered for and to no other, so a server-supplied
 * pagination link or redirect to another host never carries it.
 *
 * Values are providers so a credential refreshed during the run (a GitHub App installation token) is read at
 * send time. The header names each entry may set are fixed when it is built, so a credential that is null right now
 * is still stripped from other hosts and no provider runs to learn them. Immutable; combine with [plus].
 */
class HostCredentials private constructor(
    private val byHost: Map<String, () -> HttpAuth?>,
    private val headerNames: Set<String>,
) {
    /** The credential keyed for [url]'s host, or null when that host has none (or [url] does not parse). */
    fun authFor(url: String): HttpAuth? = parseUrl(url)?.let(::authFor)

    /** The credential keyed for [url]'s host, or null when that host has none. */
    fun authFor(url: Url): HttpAuth? = byHost[hostKey(url)]?.invoke()

    /** True when [url]'s host is keyed here, even if its credential is not available right now. */
    fun isKeyed(url: Url): Boolean = hostKey(url) in byHost

    /** Hosts (without port) the credentials are keyed for. */
    internal fun keyedHosts(): Set<String> = byHost.keys.mapTo(mutableSetOf()) { it.substringBeforeLast(':') }

    /** Header names any credential here sets: stripped from requests to hosts the credential is not keyed for. */
    fun headerNames(): Set<String> = headerNames

    /** Both registries; on the same host [other] wins. Header names of both are kept. */
    operator fun plus(other: HostCredentials): HostCredentials = HostCredentials(byHost + other.byHost, headerNames + other.headerNames)

    companion object {
        /** No credentials: every request goes out unauthenticated. */
        val EMPTY: HostCredentials = HostCredentials(emptyMap(), emptySet())

        /** [auth] keyed for [baseUrl]'s host; [EMPTY] when [auth] is null or [baseUrl] does not parse. */
        fun of(
            baseUrl: String,
            auth: HttpAuth?,
        ): HostCredentials = if (auth == null) EMPTY else of(baseUrl, setOf(auth.headerName)) { auth }

        /**
         * A credential read from [auth] at send time, keyed for [baseUrl]'s host. [headerNames] are every header
         * [auth] (or the caller, for this host) may set; they are stripped from requests to any other host.
         */
        fun of(
            baseUrl: String,
            headerNames: Set<String> = setOf(HttpHeaders.Authorization),
            auth: () -> HttpAuth?,
        ): HostCredentials {
            val url = parseUrl(baseUrl) ?: return EMPTY
            return HostCredentials(mapOf(hostKey(url) to auth), headerNames)
        }

        /** True when [url] parses and has the same host and port as [baseUrl]: the rule [authFor] applies. */
        fun sameHost(
            url: String,
            baseUrl: String,
        ): Boolean {
            val target = parseUrl(url) ?: return false
            val base = parseUrl(baseUrl) ?: return false
            return hostKey(target) == hostKey(base)
        }

        /** `host:port` with the protocol's default port filled in, lowercased. */
        internal fun hostKey(url: Url): String = "${url.host.lowercase()}:${url.port}"
    }
}
