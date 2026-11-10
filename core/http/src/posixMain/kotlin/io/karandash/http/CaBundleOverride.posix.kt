package io.karandash.http

import io.karandash.platform.PlatformEnvironment
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.curl.CurlClientEngineConfig
import io.ktor.client.engine.curl.CurlIllegalStateException

internal actual fun HttpClientConfig<*>.configureDefaultEngine(insecure: Boolean) {
    val bundle = caBundleOverride(PlatformEnvironment::getEnv)
    if (bundle == null && !insecure) return
    engine {
        val curl = this as? CurlClientEngineConfig ?: return@engine
        if (bundle != null) curl.caInfo = bundle
        if (insecure) curl.sslVerify = false
    }
}

// limit: curl reports an untrusted certificate as a plain IllegalStateException, matched by its message prefix;
// switch to a type check if Ktor gives it a dedicated exception class. EngineTransportClassificationTest pins the negatives.
internal actual fun Throwable.isEngineTransportFailure(): Boolean =
    this is CurlIllegalStateException ||
        (this is IllegalStateException && message.orEmpty().startsWith("TLS verification failed"))
