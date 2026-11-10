package io.karandash.enrichment.confluence

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfluenceLinkResolutionTest {
    @Test
    fun `cloud page links under the documented wiki base url are detected`() {
        val text = "Spec $CLOUD/spaces/ENG/pages/123456/Login+Flow and $CLOUD/spaces/OPS/pages/77"

        assertEquals(listOf("123456", "77"), ConfluenceUrlExtractor.extractPageIds(text, CLOUD))
    }

    @Test
    fun `page links from another site or another context path are ignored`() {
        val text = "https://other.atlassian.net/wiki/spaces/ENG/pages/1 https://test.atlassian.net/spaces/ENG/pages/2"

        assertEquals(emptyList(), ConfluenceUrlExtractor.extractPageIds(text, CLOUD))
    }

    @Test
    fun `data center viewpage links under the base url are detected`() {
        val text = "See $DC/pages/viewpage.action?pageId=4242 and $DC/spaces/ENG/pages/7"

        assertEquals(listOf("4242", "7"), ConfluenceUrlExtractor.extractPageIds(text, DC))
    }

    @Test
    fun `viewpage links from another host are ignored`() {
        val text = "https://other.corp/confluence/pages/viewpage.action?pageId=1"

        assertEquals(emptyList(), ConfluenceUrlExtractor.extractPageIds(text, DC))
    }

    @Test
    fun `short link resolves to the page id it redirects to`() =
        runTest {
            val requests = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    requests += request.url.encodedPath
                    if (request.url.encodedPath == "/wiki/x/AbC") {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "$CLOUD/spaces/ENG/pages/4242/Title"))
                    } else {
                        respond("<html/>", HttpStatusCode.OK)
                    }
                }
            val client = confluenceClient(CLOUD, "me@acme.com", "t", engine = engine)

            assertEquals("4242", client.resolveShortLink("AbC"))
            assertEquals(listOf("/wiki/x/AbC", "/wiki/spaces/ENG/pages/4242/Title"), requests)
            client.close()
        }

    @Test
    fun `short link that lands on a non page url or fails resolves to nothing`() =
        runTest {
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/wiki/x/home" -> respond("<html/>", HttpStatusCode.OK)
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                }
            val client = confluenceClient(CLOUD, "me@acme.com", "t", engine = engine)

            assertNull(client.resolveShortLink("home"))
            assertNull(client.resolveShortLink("gone"))
            client.close()
        }

    private companion object {
        const val CLOUD = "https://test.atlassian.net/wiki"
        const val DC = "https://wiki.corp/confluence"
    }
}
