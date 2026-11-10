package io.karandash.enrichment.github

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

class GitHubIssueClientLocalhostTest {
    @Test
    fun `localhost github enterprise is reached only with allowLocalhost`() =
        runTest {
            val paths = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    paths += request.url.encodedPath
                    respond(
                        """{"number":5,"title":"t","state":"open"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            val blocked = gitHubIssueClient(engine, allowLocalhost = false)
            assertNull(blocked.fetchIssue(5))
            blocked.close()
            assertEquals(emptyList(), paths)

            val allowed = gitHubIssueClient(engine, allowLocalhost = true)
            assertNotNull(allowed.fetchIssue(5))
            allowed.close()
            assertEquals(1, paths.size)
        }
}

private fun gitHubIssueClient(
    engine: HttpClientEngine,
    allowLocalhost: Boolean,
) = GitHubIssueClient(
    EnrichmentHttp.client(
        TestPluginContext(allowLocalhost = allowLocalhost),
        "GitHubIssue",
        "http://localhost:8080/api/v3",
        HttpAuth.Bearer("t"),
        engine,
    ),
    "http://localhost:8080/api/v3",
    "o",
    "r",
)
