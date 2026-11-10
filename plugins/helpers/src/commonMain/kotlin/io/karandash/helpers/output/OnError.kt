package io.karandash.helpers.output

import io.karandash.api.support.rethrowCancellation
import io.karandash.logger.Logger
import io.karandash.security.UrlSanitizer
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val MAX_ERROR_BODY = 200

/** What an output destination does when delivery fails: the `onError` key every destination config carries. */
@Serializable
enum class OnError {
    /** Log at warn and fail the output; the pipeline reports the failed output. */
    @SerialName("fail")
    FAIL,

    /** Log at warn and go on as if delivered. */
    @SerialName("warn")
    WARN,
}

/**
 * Runs [deliver]. A failure is logged at warn as `<what>: <message>`, with credentials stripped from any URL in it,
 * and rethrown under [OnError.FAIL]; cancellation always propagates.
 */
@Suppress("TooGenericExceptionCaught") // boundary: output delivery
suspend fun OnError.handle(
    logger: Logger,
    what: String,
    deliver: suspend () -> Unit,
) {
    try {
        deliver()
    } catch (e: Exception) {
        // boundary: output delivery; onError decides whether the failure fails the output
        e.rethrowCancellation()
        logger.warn("$what: ${e.message?.let(UrlSanitizer::sanitizeText)}")
        if (this == OnError.FAIL) throw e
    }
}

/** POSTs to [url]; a non-2xx response throws `<label>: HTTP <status> <start of the body>`. */
suspend fun HttpClient.postOrFail(
    url: String,
    label: String,
    block: HttpRequestBuilder.() -> Unit,
) {
    val response = post(url, block)
    check(response.status.isSuccess()) {
        "$label: HTTP ${response.status.value} ${response.bodyAsText().take(MAX_ERROR_BODY)}"
    }
}
