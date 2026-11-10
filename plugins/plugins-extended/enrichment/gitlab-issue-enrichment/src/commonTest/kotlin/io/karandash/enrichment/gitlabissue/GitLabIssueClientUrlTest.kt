package io.karandash.enrichment.gitlabissue

import io.karandash.http.HttpAuth
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

class GitLabIssueClientUrlTest {
    private fun recordingEngine(paths: MutableList<String>) =
        MockEngine { request ->
            paths += request.url.encodedPath
            respond("""{"iid":5,"title":"t","state":"opened"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

    @Test
    fun `project path with reserved characters is sent as one encoded segment`() =
        runTest {
            val paths = mutableListOf<String>()
            val client = gitLabIssueClient("https://gitlab.example.com", "my group/sub/proj", recordingEngine(paths))
            assertNotNull(client.fetchIssue(5))
            client.close()
            assertEquals(listOf("/api/v4/projects/my%20group%2Fsub%2Fproj/issues/5"), paths)
        }

    @Test
    fun `localhost gitlab is reached only with allowLocalhost`() =
        runTest {
            val paths = mutableListOf<String>()
            val blocked = gitLabIssueClient("http://localhost:8080", "42", recordingEngine(paths))
            assertNull(blocked.fetchIssue(5))
            blocked.close()
            assertEquals(emptyList(), paths)

            val allowed = gitLabIssueClient("http://localhost:8080", "42", recordingEngine(paths), allowLocalhost = true)
            assertNotNull(allowed.fetchIssue(5))
            allowed.close()
            assertEquals(1, paths.size)
        }
}

private fun gitLabIssueClient(
    baseUrl: String,
    projectId: String,
    engine: HttpClientEngine,
    allowLocalhost: Boolean = false,
) = GitLabIssueClient(
    EnrichmentHttp.client(
        TestPluginContext(allowLocalhost = allowLocalhost),
        "GitLabIssue",
        baseUrl,
        HttpAuth.CustomHeader("PRIVATE-TOKEN", "t"),
        engine
    ),
    baseUrl,
    projectId,
)
