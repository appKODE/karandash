package io.karandash.platforms.azuredevops

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AzureDevOpsFileContentTest {
    /** Answers [body] on [path] and 404 elsewhere; records each request as `path?query`. */
    private fun http(
        calls: MutableList<String>,
        path: String,
        body: String,
    ) = HttpClient(
        MockEngine { request ->
            calls += request.url.encodedPath + "?" + request.url.encodedQuery
            if (request.url.encodedPath == path) respond(body, HttpStatusCode.OK) else respond("", HttpStatusCode.NotFound)
        },
    )

    @Test
    fun `fetchFileContent reads the file at the ref`() =
        runTest {
            val calls = mutableListOf<String>()
            val client = AzureDevOpsProviderClient(httpClient = http(calls, "/org/proj/_apis/git/repositories/repo/items", "line1\n"))
            assertEquals("line1\n", client.fetchFileContent("org", "proj", "repo", "refs/heads/main", "src/a.kt"))
            assertEquals(
                "/org/proj/_apis/git/repositories/repo/items?path=src%2Fa.kt&versionDescriptor.version=main&versionDescriptor.versionType=branch&%24format=octetStream&api-version=7.0",
                calls.single()
            )
        }

    @Test
    fun `fetchFileContent is null for a missing file`() =
        runTest {
            val client = AzureDevOpsProviderClient(httpClient = http(mutableListOf(), "/x", ""))
            assertNull(client.fetchFileContent("org", "proj", "repo", "main", "gone.kt"))
        }
}
