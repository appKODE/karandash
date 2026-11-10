package io.karandash.providers.common

import io.karandash.cache.CacheTier
import io.karandash.cache.TieredCache
import io.karandash.crypto.sha256Hex
import io.karandash.http.CachedHttpResponse
import io.karandash.http.ETagTieredCache
import io.karandash.http.HttpAuth
import io.karandash.http.ProviderResponseCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for [BaseProviderClient] HTTP client behaviour.
 *
 * Verifies that parallel async requests work correctly - the pattern used by
 * all platform services (GitLab, GitHub, Bitbucket, Azure DevOps, Gitea, AWS CodeCommit).
 */
class BaseProviderClientTest {
    private class TestProviderClient(
        httpClient: HttpClient,
    ) : BaseProviderClient(
            baseUrl = "https://example.com",
            httpClient = httpClient,
            allowLocalhost = false,
        ) {
        override val platformName: String = "Test"

        override fun credential(): HttpAuth? = null

        suspend fun doGet(path: String): String = trackedRequest { get("$baseUrl$path").bodyAsText() }
    }

    @Test
    fun `a client built without an injected HttpClient refuses a private base URL at the first request`() =
        runTest {
            val client =
                object : BaseProviderClient("https://10.1.2.3", httpClient = null) {
                    override val platformName: String = "Test"

                    override fun credential(): HttpAuth? = null

                    suspend fun doGet(): String = trackedRequest { get("$baseUrl/x").bodyAsText() }
                }

            try {
                assertFailsWith<IllegalArgumentException> { client.doGet() }
            } finally {
                client.close()
            }
        }

    private class PagingClient(
        httpClient: HttpClient,
        cache: ProviderResponseCache,
    ) : BaseProviderClient("https://example.com", httpClient, responseCache = cache) {
        override val platformName: String = "Test"

        override fun credential(): HttpAuth? = null

        suspend fun all(): List<Int> = getAllPages("$baseUrl/items?per_page=2", "items")
    }

    @Test
    fun `paginated fetch goes through the response cache and replays the next links`() =
        runTest {
            val requested = mutableListOf<String>()
            val engine = MockEngine { request ->
                val url = request.url.toString()
                requested.add(url)
                val (body, link) =
                    if ("&page=2" in url) "[3]" to null else "[1,2]" to "<https://example.com/items?per_page=2&page=2>; rel=\"next\""
                val headers = listOfNotNull(
                    HttpHeaders.ContentType to listOf("application/json"),
                    link?.let {
                        HttpHeaders.Link to
                            listOf(it)
                    }
                )
                respond(body, HttpStatusCode.OK, headersOf(*headers.toTypedArray()))
            }
            val client = PagingClient(HttpClient(engine), ProviderResponseCache())
            assertEquals(listOf(1, 2, 3), client.all())
            assertEquals(listOf(1, 2, 3), client.all())
            assertEquals(2, requested.size, requested.toString())
            client.close()
        }

    @Test
    fun `an expired page is revalidated with its etag and a 304 serves the cached body`() =
        runTest {
            val url = "https://example.com/items?per_page=2"
            val backing = TieredCache<String, CachedHttpResponse>(name = "client-test", maxEntries = 4, tier = CacheTier.MEMORY_ONLY)
            backing.put(
                sha256Hex("Test:GET:$url"),
                CachedHttpResponse(
                    body = "[1,2]",
                    etag = "\"v1\"",
                    storedAtEpochSeconds = Clock.System.now().epochSeconds - 3_600,
                    ttlSeconds = 60
                ),
            )
            val sent = mutableListOf<String?>()
            val engine = MockEngine { request ->
                sent.add(request.headers[HttpHeaders.IfNoneMatch])
                respond("", HttpStatusCode.NotModified)
            }
            val client = PagingClient(HttpClient(engine), ProviderResponseCache(ETagTieredCache(backing, 1.minutes)))
            assertEquals(listOf(1, 2), client.all())
            assertEquals(listOf<String?>("\"v1\""), sent)
            client.close()
        }

    private fun mockClient(body: String = """{"ok":true}"""): HttpClient =
        HttpClient(
            MockEngine { _ ->
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        )

    @Test
    fun `single request succeeds with mock engine`() =
        runTest {
            val client = TestProviderClient(mockClient())
            val result = client.doGet("/api/test")
            assertEquals("""{"ok":true}""", result)
            client.close()
        }

    @Test
    fun `parallel requests succeed with mock engine`() =
        runTest {
            val client = TestProviderClient(mockClient())
            val results = coroutineScope {
                (1..4)
                    .map { i ->
                        async { client.doGet("/api/test/$i") }
                    }.awaitAll()
            }
            assertEquals(4, results.size)
            results.forEach { assertEquals("""{"ok":true}""", it) }
            client.close()
        }

    @Test
    fun `parallel requests mimic platform service fetch pattern`() =
        runTest {
            // Mimics the pattern used by GitLabPlatformService.fetchMergeRequest():
            //   coroutineScope {
            //       async { client.fetchMR() }
            //       async { client.fetchCommits() }
            //       async { client.fetchChanges() }
            //   }
            val client = TestProviderClient(mockClient())

            val results = coroutineScope {
                val mr = async { client.doGet("/api/v4/projects/1/merge_requests/1") }
                val commits = async { client.doGet("/api/v4/projects/1/merge_requests/1/commits") }
                val changes = async { client.doGet("/api/v4/projects/1/merge_requests/1/changes") }
                listOf(mr.await(), commits.await(), changes.await())
            }

            assertEquals(3, results.size)
            results.forEach { assertFalse(it.isEmpty()) }
            client.close()
        }

    @Test
    fun `four parallel requests mimic GitHub and Gitea fetch pattern`() =
        runTest {
            // GitHub and Gitea fetch 4 resources in parallel: PR, commits, files, reviews
            val client = TestProviderClient(mockClient())

            val results = coroutineScope {
                val pr = async { client.doGet("/repos/o/r/pulls/1") }
                val commits = async { client.doGet("/repos/o/r/pulls/1/commits") }
                val files = async { client.doGet("/repos/o/r/pulls/1/files") }
                val reviews = async { client.doGet("/repos/o/r/pulls/1/reviews") }
                listOf(pr.await(), commits.await(), files.await(), reviews.await())
            }

            assertEquals(4, results.size)
            results.forEach { assertFalse(it.isEmpty()) }
            client.close()
        }

    @Test
    fun `many parallel requests mimic AWS CodeCommit unbounded pattern`() =
        runTest {
            // AWS CodeCommit can launch N parallel commit fetches (unbounded by commit count)
            val client = TestProviderClient(mockClient())
            val commitCount = 20

            val results = coroutineScope {
                (1..commitCount)
                    .map { id ->
                        async { client.doGet("/commits/$id") }
                    }.awaitAll()
            }

            assertEquals(commitCount, results.size)
            results.forEach { assertFalse(it.isEmpty()) }
            client.close()
        }
}
