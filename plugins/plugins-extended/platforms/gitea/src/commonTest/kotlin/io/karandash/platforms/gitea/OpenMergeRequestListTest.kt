package io.karandash.platforms.gitea

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
    fun `lists open PR numbers and keeps only the base branch`() =
        runTest {
            val urls = mutableListOf<Url>()
            val body = """[{"number":1,"base":{"ref":"main"}},{"number":2,"base":{"ref":"dev"}},{"number":3,"base":{"ref":"main"}}]"""
            val client = GiteaProviderClient("https://gitea.example.com", http(body, urls), "t")

            assertEquals(listOf("1", "3"), client.listOpenPullRequestNumbers("o", "r", "main", 5))
            assertEquals("open", urls.single().parameters["state"])
        }
}
