package io.karandash.providers.common

import io.karandash.api.PluginContext
import io.karandash.api.support.suspendCatching
import io.karandash.http.HostCredentials
import io.karandash.http.HttpAuth
import io.karandash.http.ProviderHttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine

/**
 * The one way an enrichment provider builds its HTTP client: logger and `allowLocalhost` come from the
 * [PluginContext], so no provider can forget `--allow-private-network`, and [auth] is sent only to [baseUrl]'s host.
 */
object EnrichmentHttp {
    fun client(
        context: PluginContext,
        providerName: String,
        baseUrl: String,
        auth: HttpAuth,
        engine: HttpClientEngine? = null,
    ): HttpClient =
        ProviderHttpClientFactory.createProvider(
            providerName = providerName,
            engine = engine,
            credentials = HostCredentials.of(baseUrl, auth),
            logger = context.logger,
            allowLocalhost = context.allowLocalhost,
        )

    /**
     * Atlassian auth: Basic `email:token` for a Cloud API token, `Bearer` for a Server/Data Center personal access
     * token when no email is set.
     */
    fun atlassianAuth(
        email: String?,
        token: String,
    ): HttpAuth = if (email.isNullOrBlank()) HttpAuth.Bearer(token) else HttpAuth.Basic(email, token)
}

/**
 * Runs an enrichment fetch [block]; a failure (cancellation rethrown) goes to [log] as `<failure>: <message>` and
 * gives null. [log] picks the level: `logger::warn` for a missing result, `logger::debug` for an optional extra.
 */
inline fun <T> fetchOrNull(
    failure: String,
    log: (String) -> Unit,
    block: () -> T,
): T? = suspendCatching(block).onFailure { log("$failure: ${it.message}") }.getOrNull()
