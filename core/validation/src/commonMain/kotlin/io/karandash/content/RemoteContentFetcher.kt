package io.karandash.content

import io.karandash.config.AuthConfig

/**
 * Injectable abstraction for performing HTTP GET requests in [ContentResolver].
 *
 * The production implementation is `RemoteConfigFetcher.asContentFetcher()` in `core-config-pipeline`
 * (strict SSRF policy, HTTPS-only auth, no redirects, 1 MiB body cap), wired by the CLI at config load,
 * so that `core-validation` does not depend on Ktor directly.
 */
fun interface RemoteContentFetcher {
    /**
     * Fetch the content at [url], applying [auth] credentials if provided.
     *
     * @param url The URL to fetch.
     * @param auth Optional named auth configuration to apply to the request.
     * @return The response body as a string.
     * @throws [io.karandash.api.exception.ContentResolveException] on HTTP or network failure.
     */
    suspend fun fetch(
        url: String,
        auth: AuthConfig?
    ): String
}
