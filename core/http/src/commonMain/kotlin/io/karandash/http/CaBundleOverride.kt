package io.karandash.http

import io.karandash.platform.PlatformEnvironment
import io.ktor.client.HttpClientConfig
import kotlinx.atomicfu.atomic

/**
 * Applies host trust settings to the default engine. Native curl builds and the JVM (CIO, the bundle's certificates
 * as its only trust anchors) honour [caBundleOverride] and, when [insecure], skip certificate verification; Windows uses the system store.
 */
internal expect fun HttpClientConfig<*>.configureDefaultEngine(insecure: Boolean)

/**
 * True for an engine failure that is a transport/TLS problem but not an `IOException` (CIO throws the
 * certificate `GeneralSecurityException` as is, curl throws `CurlIllegalStateException`), so callers that map
 * `IOException` to a network error see these too instead of an internal error.
 */
internal expect fun Throwable.isEngineTransportFailure(): Boolean

/**
 * CA bundle file named by the standard `SSL_CERT_FILE` / `CURL_CA_BUNDLE` variables, or `null`
 * when neither is set to a non-blank value.
 */
internal fun caBundleOverride(env: (String) -> String?): String? =
    listOf("SSL_CERT_FILE", "CURL_CA_BUNDLE")
        .firstNotNullOfOrNull { name -> env(name)?.takeIf { it.isNotBlank() } }

/** Environment variable listing the hosts whose HTTPS certificates are not verified (see [tlsInsecureHosts]). */
internal const val TLS_INSECURE_HOSTS_ENV = "KARANDASH_TLS_INSECURE_HOSTS"

private val tlsInsecureWarned = atomic(false)

/**
 * Lowercased hosts from the comma-separated [TLS_INSECURE_HOSTS_ENV], e.g. a GitLab reached by IP while its DNS is
 * down. Only a client whose credentials are keyed for listed hosts alone skips verification ([skipsTlsVerification]).
 */
internal fun tlsInsecureHosts(env: (String) -> String?): Set<String> =
    env(TLS_INSECURE_HOSTS_ENV)
        .orEmpty()
        .split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

/** True when [credentials] are keyed for at least one host and every one of them is in [insecureHosts]. */
internal fun skipsTlsVerification(
    credentials: HostCredentials?,
    insecureHosts: Set<String>,
): Boolean {
    val hosts = credentials?.keyedHosts().orEmpty()
    return hosts.isNotEmpty() && hosts.all { it in insecureHosts }
}

/** Warns on stderr, once per process, that certificates of [hosts] are not verified. */
internal fun warnTlsVerificationSkipped(hosts: Set<String>) {
    if (tlsInsecureWarned.compareAndSet(expect = false, update = true)) {
        PlatformEnvironment.stderrPrintln("[WARN] $TLS_INSECURE_HOSTS_ENV: HTTPS certificates of ${hosts.sorted()} are not verified")
    }
}
