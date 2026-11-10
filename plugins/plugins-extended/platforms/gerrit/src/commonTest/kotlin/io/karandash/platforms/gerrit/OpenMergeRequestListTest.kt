package io.karandash.platforms.gerrit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenMergeRequestListTest {
    private fun http(
        body: String,
        urls: MutableList<Url>,
    ) = HttpClient(
        MockEngine { request ->
            urls += request.url
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    @Test
    fun `lists open change numbers past the XSSI prefix`() =
        runTest {
            val urls = mutableListOf<Url>()
            val body = ")]}'\n[{\"_number\":31},{\"_number\":32}]"
            val client = GerritProviderClient("https://gerrit.example.com", http(body, urls))

            assertEquals(listOf("31", "32"), client.listOpenChangeNumbers("proj", "main", 5))

            val url = urls.single()
            assertEquals("status:open project:proj branch:main", url.parameters["q"])
            assertEquals("5", url.parameters["n"])
        }

    @Test
    fun `pages with S while the last change says more changes exist`() =
        runTest {
            val urls = mutableListOf<Url>()
            val pages = mapOf(
                "0" to ")]}'\n[{\"_number\":1},{\"_number\":2,\"_more_changes\":true}]",
                "2" to ")]}'\n[{\"_number\":3}]",
            )
            val engine = MockEngine { request ->
                urls += request.url
                respond(pages.getValue(request.url.parameters["S"].orEmpty()), HttpStatusCode.OK)
            }
            val client = GerritProviderClient("https://gerrit.example.com", HttpClient(engine))

            assertEquals(listOf("1", "2", "3"), client.listOpenChangeNumbers("proj", "main", 5))
            assertEquals(listOf("5", "3"), urls.map { it.parameters["n"] })
        }
}
