package io.karandash.cli.cache

import io.karandash.cache.remote.RemoteCacheFetcher
import io.karandash.cache.remote.RemoteCacheResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/**
 * Ktor-backed implementation of [RemoteCacheFetcher].
 *
 * Delegates all HTTP operations to the provided [HttpClient].
 * The client is NOT closed by this class - lifecycle is managed by the caller.
 *
 * @param httpClient Ktor HttpClient to use for requests.
 * @param auth `cache.remote.auth`: sent as the `Authorization` header - verbatim when it already names a
 *   scheme (`Basic …`, `Bearer …`), otherwise as a bearer token. Never logged.
 * @param maxBodyBytes `cache.remote.max-blob-size`: a GET body over it (by `Content-Length`, or once that many bytes
 *   were read) is not read into memory and answers [HTTP_PAYLOAD_TOO_LARGE] without a body.
 */
class KtorRemoteCacheFetcher(
    private val httpClient: HttpClient,
    auth: String? = null,
    private val maxBodyBytes: Long = Long.MAX_VALUE,
) : RemoteCacheFetcher {
    private val authorization: String? =
        auth?.trim()?.takeIf { it.isNotEmpty() }?.let { if (' ' in it) it else "Bearer $it" }

    override suspend fun request(
        method: String,
        url: String,
        body: ByteArray?,
        headers: Map<String, String>,
    ): RemoteCacheResponse =
        when (method.uppercase()) {
            "GET" -> {
                httpClient.prepareGet(url) { authorize(headers) }.execute { response -> response.boundedBody() }
            }

            "HEAD" -> {
                RemoteCacheResponse(httpClient.head(url) { authorize(headers) }.status.value)
            }

            "PUT" -> {
                val response = httpClient.put(url) {
                    authorize(headers)
                    if (body != null) setBody(body)
                }
                RemoteCacheResponse(response.status.value)
            }

            "DELETE" -> {
                RemoteCacheResponse(httpClient.delete(url) { authorize(headers) }.status.value)
            }

            else -> {
                error("Unsupported HTTP method for remote cache: $method")
            }
        }

    private suspend fun HttpResponse.boundedBody(): RemoteCacheResponse {
        if (!status.isSuccess()) return RemoteCacheResponse(status.value)
        if ((contentLength() ?: 0L) > maxBodyBytes) return RemoteCacheResponse(HTTP_PAYLOAD_TOO_LARGE)
        val limit = if (maxBodyBytes == Long.MAX_VALUE) maxBodyBytes else maxBodyBytes + 1
        val bytes = bodyAsChannel().readRemaining(limit).readByteArray()
        return if (bytes.size > maxBodyBytes) RemoteCacheResponse(HTTP_PAYLOAD_TOO_LARGE) else RemoteCacheResponse(status.value, bytes)
    }

    /** Sends [headers]; the `auth` header only when they carry no `Authorization` of their own (a signed S3 request). */
    private fun HttpRequestBuilder.authorize(headers: Map<String, String>) {
        headers.forEach { (name, value) -> header(name, value) }
        if (headers.keys.none { it.equals(HttpHeaders.Authorization, ignoreCase = true) }) {
            authorization?.let { header(HttpHeaders.Authorization, it) }
        }
    }

    companion object {
        /** Status reported for a body over `maxBodyBytes`; the backend treats it as a failed pull. */
        const val HTTP_PAYLOAD_TOO_LARGE = 413
    }
}
