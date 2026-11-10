package io.karandash.api.exception

/**
 * Exception thrown when a provider HTTP API call fails with a non-success status code.
 *
 * Carries structured information about the failure so that upstream error handlers
 * can produce actionable, platform-specific error messages without pattern-matching
 * on raw exception text.
 *
 * @param statusCode HTTP status code (e.g. 401, 403, 404, 429, 500)
 * @param statusDescription HTTP status description (e.g. "Unauthorized", "Forbidden")
 * @param platformName Human-readable platform name (e.g. "GitLab", "GitHub")
 * @param requestLabel API endpoint label for context (e.g. "GET /projects/foo/merge_requests/1")
 * @param responseBody Truncated response body for diagnostics
 */
public class ProviderApiException(
    public val statusCode: Int,
    public val statusDescription: String,
    public val platformName: String,
    public val requestLabel: String,
    public val responseBody: String,
    message: String = "$platformName API request failed ($requestLabel): " +
        "HTTP $statusCode $statusDescription. Response: $responseBody",
) : KarandashException(message) {
    /**
     * Whether this is an authentication/authorization failure (401, 403).
     */
    public val isAuthError: Boolean get() = statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN

    /**
     * Whether this is an "invalid token" error (401).
     */
    public val isUnauthorized: Boolean get() = statusCode == HTTP_UNAUTHORIZED

    /**
     * Whether this is an "insufficient permissions" error (403).
     */
    public val isForbidden: Boolean get() = statusCode == HTTP_FORBIDDEN

    /**
     * Whether this is a "not found" error (404).
     * On many platforms (GitLab, GitHub) a 404 can also indicate insufficient access.
     */
    public val isNotFound: Boolean get() = statusCode == HTTP_NOT_FOUND

    /**
     * Whether this is a rate-limit error (429).
     */
    public val isRateLimited: Boolean get() = statusCode == HTTP_TOO_MANY_REQUESTS

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
