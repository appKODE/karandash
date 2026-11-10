package io.karandash.enrichment.ci

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
import kotlin.test.assertNull

class CiPipelineClientUrlTest {
    private fun recordingEngine(paths: MutableList<String>) =
        MockEngine { request ->
            paths += request.url.encodedPath
            respond("""[{"id":7,"status":"failed"}]""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

    @Test
    fun `group project path is sent as one encoded segment`() =
        runTest {
            val paths = mutableListOf<String>()
            val client = gitLabClient("https://gitlab.example.com", "group/sub/proj", "t", engine = recordingEngine(paths))
            assertEquals(7L, client.fetchLatestPipelineId(3))
            client.close()
            assertEquals(listOf("/api/v4/projects/group%2Fsub%2Fproj/merge_requests/3/pipelines"), paths)
        }

    @Test
    fun `localhost gitlab is reached only with allowLocalhost`() =
        runTest {
            val paths = mutableListOf<String>()
            val blocked = gitLabClient("http://localhost:8080", "42", "t", engine = recordingEngine(paths))
            assertNull(blocked.fetchLatestPipelineId(1))
            blocked.close()
            assertEquals(emptyList(), paths)

            val allowed = gitLabClient("http://localhost:8080", "42", "t", engine = recordingEngine(paths), allowLocalhost = true)
            assertEquals(7L, allowed.fetchLatestPipelineId(1))
            allowed.close()
            assertEquals(1, paths.size)
        }
}

/** A GitLab [CiPipelineClient] the way the provider builds one, with a private token. */
internal fun gitLabClient(
    baseUrl: String,
    projectId: String,
    token: String,
    engine: HttpClientEngine,
    allowLocalhost: Boolean = false,
) = CiPipelineClient(
    httpClient = EnrichmentHttp.client(
        TestPluginContext(allowLocalhost = allowLocalhost),
        "CI",
        baseUrl,
        HttpAuth.CustomHeader(PRIVATE_TOKEN_HEADER, token),
        engine,
    ),
    baseUrl = baseUrl,
    projectId = projectId,
)
