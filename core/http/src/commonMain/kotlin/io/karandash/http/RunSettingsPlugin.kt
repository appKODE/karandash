package io.karandash.http

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.run.RunScopedServices
import io.karandash.duration.KarandashDuration
import io.karandash.http.ProviderHttpClientFactory.applyRetry
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.retry
import io.ktor.client.plugins.timeout

/**
 * Applies the calling run's [ProviderHttpClientFactory.HTTP_SETTINGS] to each request: `timeouts.http` (unless the
 * client was built with an explicit timeout) and `http.retry`. Modules build their clients in `initialize()`, before
 * any request, so reading the settings at send time is what ties a client to the run that uses it; two runs in one
 * process each get their own.
 *
 * Installed by [ProviderHttpClientFactory.create] when no run services are passed, before HttpTimeout and
 * HttpRequestRetry so they see the per-request values.
 */
@OptIn(ExperimentalKarandashApi::class)
internal fun runSettingsPlugin(
    explicitTimeout: Boolean,
    connectTimeout: KarandashDuration?,
    explicitRetry: Boolean = false,
) = createClientPlugin("RunHttpSettings") {
    on(Send) { request ->
        val settings = RunScopedServices.current()?.get(ProviderHttpClientFactory.HTTP_SETTINGS)
        val timeout = settings?.timeout?.takeUnless { explicitTimeout }
        if (timeout != null) {
            request.timeout {
                requestTimeoutMillis = timeout.millis
                connectTimeoutMillis = (connectTimeout ?: timeout).millis
                socketTimeoutMillis = timeout.millis
            }
        }
        settings?.retry?.takeUnless { explicitRetry }?.let { retry -> request.retry { applyRetry(retry) } }
        proceed(request)
    }
}
