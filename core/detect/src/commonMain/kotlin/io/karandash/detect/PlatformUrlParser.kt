package io.karandash.detect

import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.platform.PlatformEnvironment

/**
 * Shared parser for PR/MR URLs across CLI features (validate, CI detection, doctor).
 *
 * Dispatches to registered provider modules via [PlatformServiceModule.parseMergeRequestUrl].
 * The returned [ParsedMergeRequestUrl.platform] is normalised to a canonical platform ID
 * (e.g. "gitlab", "azure-devops") regardless of what raw value the provider sets.
 */
class PlatformUrlParser(
    private val providers: List<PlatformServiceModule>
) {
    private companion object {
        private const val HTTP_PREFIX = "http://"
        private const val HTTPS_PREFIX = "https://"
    }

    /**
     * Parse a PR/MR URL from any supported platform.
     */
    suspend fun parse(
        url: String,
        warnOnInsecureHttp: Boolean = false
    ): ParsedMergeRequestUrl? {
        if (warnOnInsecureHttp && url.startsWith(HTTP_PREFIX)) {
            PlatformEnvironment.stderrPrintln(
                "Warning: Insecure HTTP URL detected. Consider using HTTPS instead: $HTTPS_PREFIX${url.removePrefix(HTTP_PREFIX)}"
            )
        }

        // only the scheme: an `http://` inside the query (e.g. a redirect parameter) stays as written
        val secureUrl = if (url.startsWith(HTTP_PREFIX)) HTTPS_PREFIX + url.removePrefix(HTTP_PREFIX) else url

        var result: ParsedMergeRequestUrl? = null
        for (provider in providers) {
            result = provider.parseMergeRequestUrl(secureUrl)
            if (result != null) break
        }
        result ?: return null

        val canonicalPlatform = resolveCanonicalPlatform(result.platform)
        return if (canonicalPlatform != result.platform) result.copy(platform = canonicalPlatform) else result
    }

    private suspend fun resolveCanonicalPlatform(rawPlatform: String): String = resolveCanonicalPlatform(rawPlatform, providers)
}
