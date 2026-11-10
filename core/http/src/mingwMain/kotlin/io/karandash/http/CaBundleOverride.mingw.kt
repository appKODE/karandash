package io.karandash.http

import io.ktor.client.HttpClientConfig

// limit: Windows keeps verifying (KARANDASH_TLS_INSECURE_HOSTS unsupported); WinHttpClientEngineConfig.sslVerify when needed.
internal actual fun HttpClientConfig<*>.configureDefaultEngine(
    @Suppress("UnusedParameter") insecure: Boolean,
) = Unit

internal actual fun Throwable.isEngineTransportFailure(): Boolean = false
