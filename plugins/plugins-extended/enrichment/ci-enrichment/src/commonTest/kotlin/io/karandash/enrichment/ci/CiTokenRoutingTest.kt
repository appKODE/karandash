package io.karandash.enrichment.ci

import io.karandash.api.context.EnrichmentContext
import io.karandash.models.CiJobStatus
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins CiPipelineClient already sends a CI_JOB_TOKEN fallback as `JOB-TOKEN`. */
class CiTokenRoutingTest {
    private val failedMr = createTestMergeRequest().copy(ciJobs = listOf(CiJobStatus("build", "failed")))

    private fun recordingEngine(requests: MutableList<HttpRequestData>) =
        MockEngine { request ->
            requests += request
            respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

    private suspend fun initAndEnrich(
        config: String,
        env: Map<String, String>,
    ): Pair<List<HttpRequestData>, TestPluginContext> {
        val requests = mutableListOf<HttpRequestData>()
        val ctx = TestPluginContext(KarandashJson.Default.parseToJsonElement(config), env)
        val provider = CiEnrichmentProvider(recordingEngine(requests))
        provider.initialize(ctx)
        provider.enrich(failedMr, JsonObject(emptyMap()), EnrichmentContext.EMPTY)
        provider.shutdown()
        return requests to ctx
    }

    @Test
    fun `github token is never sent to gitlab ci api`() =
        runTest {
            val (requests, _) = initAndEnrich(API_CONFIG, mapOf("GITHUB_TOKEN" to GITHUB_SECRET))
            val leaked = requests.filter { req -> req.headers.entries().any { (_, v) -> GITHUB_SECRET in v } }
            assertEquals(emptyList(), leaked.map { it.url.toString() })
        }

    @Test
    fun `job trace redirect to another host carries no token`() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val engine = MockEngine { request ->
                requests += request
                if (request.url.host == "gitlab.acme.com") {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://storage.example.net/trace"))
                } else {
                    respond("log", HttpStatusCode.OK)
                }
            }
            val client = gitLabClient(baseUrl = "https://gitlab.acme.com", projectId = "7", token = "glpat-ok", engine = engine)

            assertEquals("log", client.fetchJobTrace(1))
            assertEquals(listOf("glpat-ok", null), requests.map { it.headers["PRIVATE-TOKEN"] })
        }

    @Test
    fun `gitlab token is still sent as private token`() =
        runTest {
            val (requests, _) = initAndEnrich(API_CONFIG, mapOf("GITLAB_TOKEN" to "glpat-ok", "GITHUB_TOKEN" to GITHUB_SECRET))
            assertTrue(requests.isNotEmpty(), "expected a pipelines request")
            assertTrue(requests.all { it.headers["PRIVATE-TOKEN"] == "glpat-ok" })
        }

    @Test
    fun `ci job token is sent as job token when no private token is configured`() =
        runTest {
            val (requests, _) = initAndEnrich(API_CONFIG, mapOf("CI_JOB_TOKEN" to "job-123"))
            assertTrue(requests.isNotEmpty(), "expected a pipelines request")
            assertTrue(requests.all { it.headers["JOB-TOKEN"] == "job-123" && it.headers["PRIVATE-TOKEN"] == null })
        }

    @Test
    fun `configured private token wins over ci job token`() =
        runTest {
            val (requests, _) = initAndEnrich(API_CONFIG, mapOf("GITLAB_TOKEN" to "glpat-ok", "CI_JOB_TOKEN" to "job-123"))
            assertTrue(requests.isNotEmpty(), "expected a pipelines request")
            assertTrue(requests.all { it.headers["PRIVATE-TOKEN"] == "glpat-ok" && it.headers["JOB-TOKEN"] == null })
        }

    @Test
    fun `non gitlab platform skips the ci api with a warning`() =
        runTest {
            val config = """{"platform":"bitbucket","url":"https://gitlab.acme.com","project-id":"7","token":"t"}"""
            val (requests, ctx) = initAndEnrich(config, emptyMap())
            assertEquals(emptyList(), requests.map { it.url.toString() })
            assertTrue(ctx.warnings.any { "platform" in it && "bitbucket" in it }, "warnings: ${ctx.warnings}")
        }

    @Test
    fun `blank private token falls back to ci job token`() =
        runTest {
            val (requests, _) = initAndEnrich(API_CONFIG, mapOf("GITLAB_TOKEN" to "", "CI_JOB_TOKEN" to "job-123"))
            assertTrue(requests.isNotEmpty(), "expected a pipelines request")
            assertTrue(requests.all { it.headers["JOB-TOKEN"] == "job-123" && it.headers["PRIVATE-TOKEN"] == null })
        }

    @Test
    fun `private api url sends no request and no token`() =
        runTest {
            for (url in listOf("https://10.1.2.3", "https://169.254.169.254")) {
                val config = """{"url":"$url","project-id":"7"}"""
                val (requests, _) = initAndEnrich(config, mapOf("GITLAB_TOKEN" to "glpat-ok"))
                assertEquals(emptyList(), requests.map { it.url.toString() }, url)
            }
        }

    private companion object {
        const val GITHUB_SECRET = "ghp_never_to_gitlab"
        const val API_CONFIG = """{"url":"https://gitlab.acme.com","project-id":"7"}"""
    }
}
