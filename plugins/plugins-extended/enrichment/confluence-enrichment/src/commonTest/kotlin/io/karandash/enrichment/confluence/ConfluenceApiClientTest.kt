package io.karandash.enrichment.confluence

import io.karandash.providers.common.EnrichmentHttp
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfluenceApiClientTest {
    private val baseUrl = "https://test.atlassian.net/wiki"

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun pageJson(
        id: String = "123456",
        title: String = "My Page",
        version: Int = 3,
        body: String = "<p>Hello world</p>",
    ) = """
        {
          "id": "$id",
          "title": "$title",
          "version": { "number": $version },
          "body": {
            "storage": { "value": "$body" }
          },
          "_links": { "webui": "/spaces/SPACE/pages/$id" }
        }
        """.trimIndent()

    @Test
    fun fetchPageReturnsContentOnSuccess() =
        runTest {
            val engine = MockEngine { respond(pageJson(), HttpStatusCode.OK, jsonHeaders()) }
            val client = confluenceClient(baseUrl, "user@example.com", "token", engine = engine)

            val result = client.fetchPage("123456")

            assertNotNull(result)
            assertEquals("123456", result.pageId)
            assertEquals("My Page", result.title)
            assertEquals(3, result.version)
            assertTrue(result.markdown.contains("Hello world"))
            client.close()
        }

    @Test
    fun fetchPageReturnsNullOn404() =
        runTest {
            val engine = MockEngine {
                respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, jsonHeaders())
            }
            val client = confluenceClient(baseUrl, "user@example.com", "token", engine = engine)

            assertNull(client.fetchPage("999"))
            client.close()
        }

    @Test
    fun fetchPageReturnsNullOn401() =
        runTest {
            val engine = MockEngine {
                respond("""{"message":"Unauthorized"}""", HttpStatusCode.Unauthorized, jsonHeaders())
            }
            val client = confluenceClient(baseUrl, "user@example.com", "bad-token", engine = engine)

            assertNull(client.fetchPage("123456"))
            client.close()
        }

    @Test
    fun fetchPageConvertsStorageXmlToMarkdown() =
        runTest {
            val engine = MockEngine {
                respond(
                    pageJson(body = "<h2>Section</h2><ul><li>Item</li></ul>"),
                    HttpStatusCode.OK,
                    jsonHeaders(),
                )
            }
            val client = confluenceClient(baseUrl, "user@example.com", "token", engine = engine)

            val result = client.fetchPage("123456")

            assertNotNull(result)
            assertTrue(result.markdown.contains("## Section"))
            assertTrue(result.markdown.contains("- Item"))
            client.close()
        }
}

class ConfluenceUrlExtractorTest {
    private val base = "https://acme.atlassian.net/wiki"

    @Test
    fun extractsPageIdFromWikiUrl() {
        val text = "See $base/wiki/spaces/ENG/pages/123456/My+Page for details"
        val ids = ConfluenceUrlExtractor.extractPageIds(text, base)
        assertEquals(listOf("123456"), ids)
    }

    @Test
    fun ignoresUrlsFromDifferentBase() {
        val text = "https://other.atlassian.net/wiki/spaces/ENG/pages/999/Title"
        val ids = ConfluenceUrlExtractor.extractPageIds(text, base)
        assertTrue(ids.isEmpty())
    }

    @Test
    fun deduplicatesPageIds() {
        val text = "$base/wiki/spaces/A/pages/42 and $base/wiki/spaces/B/pages/42"
        val ids = ConfluenceUrlExtractor.extractPageIds(text, base)
        assertEquals(listOf("42"), ids)
    }

    @Test
    fun extractsShortLinkTokens() {
        val text = "Link: $base/x/AbCdEf123"
        val tokens = ConfluenceUrlExtractor.extractShortLinks(text, base)
        assertEquals(listOf("AbCdEf123"), tokens)
    }
}

/** A [ConfluenceApiClient] built the way the provider builds it. */
internal fun confluenceClient(
    baseUrl: String,
    email: String?,
    token: String,
    engine: HttpClientEngine,
) = ConfluenceApiClient(
    EnrichmentHttp.client(TestPluginContext(), "Confluence", baseUrl, EnrichmentHttp.atlassianAuth(email, token), engine),
    baseUrl,
)
