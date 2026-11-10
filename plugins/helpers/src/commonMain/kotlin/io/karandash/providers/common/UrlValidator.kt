package io.karandash.providers.common

import io.karandash.http.SsrfUrlValidator

/**
 * URL validation utility for provider clients.
 *
 * Delegates to [SsrfUrlValidator] for the actual validation logic.
 */
object UrlValidator {
    /**
     * Validates a base URL for use with external APIs.
     *
     * @param url The URL to validate.
     * @param allowHttp If true, allows HTTP (default: false, HTTPS only).
     * @param allowLocalhost If true, skips private/loopback IP checks and allows HTTP.
     * @param allowPrivateNetwork If true, permits private and dotless hosts only. A client built before its run's
     *   [io.karandash.http.NetworkPolicy] is known passes true: the factory's clients check each request against it.
     * @return The validated and normalized URL (trailing slash removed).
     * @throws IllegalArgumentException if validation fails.
     */
    fun validateBaseUrl(
        url: String,
        allowHttp: Boolean = false,
        allowLocalhost: Boolean = false,
        allowPrivateNetwork: Boolean = false,
    ): String = SsrfUrlValidator.validateBaseUrl(url, allowHttp, allowLocalhost, allowPrivateNetwork)

    /**
     * Validates that a pagination or redirect URL belongs to the expected base domain.
     *
     * @throws IllegalArgumentException if the URL doesn't match the expected base.
     */
    fun validateUrlMatchesBase(
        url: String,
        expectedBase: String,
    ) = SsrfUrlValidator.validateUrlMatchesBase(url, expectedBase)
}
