package io.karandash.testfixtures

import io.karandash.http.ProviderHttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One canned answer of a [RecordingMockServer]: requests matching [matches] get [body] with [contentType]. */
class MockRoute(
    val matches: (HttpRequestData) -> Boolean,
    val body: String,
    val contentType: String = JSON,
    val status: HttpStatusCode = HttpStatusCode.OK,
)

/** Route matching any request whose encoded path ends with [suffix] (query string ignored). */
fun pathEndsWith(
    suffix: String,
    body: String,
    contentType: String = JSON,
): MockRoute = MockRoute({ it.url.encodedPath.endsWith(suffix) }, body, contentType)

/** Route matching [method] requests whose encoded path ends with [suffix] (query string ignored). */
fun route(
    method: HttpMethod,
    suffix: String,
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): MockRoute = MockRoute({ it.method == method && it.url.encodedPath.endsWith(suffix) }, body, status = status)

/** Route for an AWS JSON-1.1 API operation, matched on the `X-Amz-Target` header. */
fun amzTarget(
    target: String,
    body: String,
): MockRoute = MockRoute({ it.headers[AMZ_TARGET] == target }, body, "application/x-amz-json-1.1")

private const val JSON = "application/json"

/** `X-Amz-Target` header naming the AWS API operation of a request. */
const val AMZ_TARGET = "X-Amz-Target"

/**
 * Ktor [MockEngine] that records every request and answers from [routes] (first match wins).
 * Unknown requests get 404 so a client calling an endpoint the real API does not have fails loudly.
 * Hand [engine] to a platform service's engine constructor parameter to drive it offline.
 */
class RecordingMockServer(
    private val routes: List<MockRoute>,
) {
    private val lock = Mutex()
    private val recorded = mutableListOf<HttpRequestData>()

    val engine: MockEngine = MockEngine { request ->
        lock.withLock { recorded += request }
        val route = routes.firstOrNull { it.matches(request) }
        if (route == null) {
            respond(content = """{"message":"404 Not Found"}""", status = HttpStatusCode.NotFound, headers = JSON_HEADERS)
        } else {
            respond(
                content = route.body,
                status = route.status,
                headers = headersOf(HttpHeaders.ContentType, route.contentType),
            )
        }
    }

    suspend fun requests(): List<HttpRequestData> = lock.withLock { recorded.toList() }

    /** A production-configured client (content negotiation, SSRF plugin) backed by this mock engine. */
    fun httpClient(): HttpClient = ProviderHttpClientFactory.create(engine = engine)

    private companion object {
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, JSON)
    }
}
