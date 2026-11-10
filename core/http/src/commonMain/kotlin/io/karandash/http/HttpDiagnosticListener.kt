package io.karandash.http

/**
 * Callback interface for HTTP request diagnostic collection.
 *
 * Implementations receive timing and metadata for every HTTP request made
 * through [ProviderHttpClientFactory]. This decouples diagnostic collection
 * from the HTTP layer - the factory and client plugins know nothing about
 * the diagnostic subsystem.
 *
 * Used by the CLI pipeline to feed HTTP request data into the
 * [io.karandash.diagnostics.DiagnosticCollector] without core/http
 * depending on core/diagnostics.
 */
fun interface HttpDiagnosticListener {
    /**
     * Called after an HTTP request completes (success or failure).
     *
     * @param method HTTP method (GET, POST, PUT, etc.)
     * @param url Request URL (may contain path but never auth tokens)
     * @param statusCode HTTP status code, or null if the request failed before receiving a response
     * @param durationMs Round-trip duration in milliseconds
     * @param responseSizeBytes Response body size from Content-Length header, or null if unknown
     * @param error Error message if the request failed, or null on success
     */
    fun onRequestComplete(
        method: String,
        url: String,
        statusCode: Int?,
        durationMs: Long,
        responseSizeBytes: Long?,
        error: String?,
    )
}
