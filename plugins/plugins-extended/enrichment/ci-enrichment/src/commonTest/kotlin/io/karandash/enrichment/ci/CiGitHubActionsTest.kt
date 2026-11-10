package io.karandash.enrichment.ci

import io.karandash.api.context.EnrichmentContext
import io.karandash.models.CiJobStatus
import io.karandash.models.DiffRefs
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CI enrichment on GitHub Actions and the GitLab CI env defaults. */
class CiGitHubActionsTest {
    private val failedMr = createTestMergeRequest().copy(
        ciJobs = listOf(CiJobStatus("build", "failed")),
        diffRefs = DiffRefs(baseSha = "base", headSha = "abc123", startSha = "base"),
    )

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun gitHubEngine(requests: MutableList<HttpRequestData>) =
        MockEngine { request ->
            requests += request
            val path = request.url.encodedPath
            when {
                path.endsWith("/actions/runs") -> respond(RUNS, HttpStatusCode.OK, json)
                path.endsWith("/runs/11/jobs") -> respond(JOBS, HttpStatusCode.OK, json)
                path.endsWith("/jobs/21/logs") -> respond("step 1\nerror: Unresolved reference foo\n", HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

    @Test
    fun `github actions env selects the github api and reports failed jobs with log snippets`() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val env = mapOf("GITHUB_ACTIONS" to "true", "GITHUB_REPOSITORY" to "acme/app", "GITHUB_TOKEN" to "ghs-1")
            val provider = CiEnrichmentProvider(gitHubEngine(requests))
            provider.initialize(TestPluginContext(JsonObject(emptyMap()), env))

            val result = provider.enrich(failedMr, JsonObject(emptyMap()), EnrichmentContext.EMPTY)
            provider.shutdown()

            assertNull(provider.skipReason())
            val jobs = KarandashJson.Default.decodeFromJsonElement(
                ListSerializer(FailedJob.serializer()),
                result.getValue(EnrichmentKeys.CI_FAILED_JOBS),
            )
            assertEquals(listOf("unit-tests"), jobs.map { it.name })
            assertEquals("Run tests", jobs.single().stage)
            assertEquals("https://github.com/acme/app/actions/runs/11/job/21", jobs.single().jobUrl)
            assertTrue(
                jobs
                    .single()
                    .errorSnippet
                    .orEmpty()
                    .contains("Unresolved reference"),
                jobs.single().errorSnippet
            )
            assertEquals(
                "head_sha=abc123",
                requests
                    .first()
                    .url.encodedQuery
                    .substringBefore('&')
            )
            assertTrue(requests.all { it.url.host == "api.github.com" })
            assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer ghs-1" })
        }

    @Test
    fun `github without a token gives a skip reason`() =
        runTest {
            val provider = CiEnrichmentProvider(gitHubEngine(mutableListOf()))
            provider.initialize(
                TestPluginContext(
                    JsonObject(emptyMap()),
                    mapOf(
                        "GITHUB_ACTIONS" to "true",
                        "GITHUB_REPOSITORY" to "acme/app"
                    )
                )
            )

            assertTrue(provider.skipReason().orEmpty().contains("GITHUB_TOKEN"), provider.skipReason())
        }

    @Test
    fun `gitlab ci env supplies server url and project id`() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val engine = MockEngine { request ->
                requests += request
                respond("[]", HttpStatusCode.OK, json)
            }
            val env = mapOf("CI_SERVER_URL" to "https://gitlab.acme.com", "CI_PROJECT_ID" to "42", "CI_JOB_TOKEN" to "job-1")
            val provider = CiEnrichmentProvider(engine)
            provider.initialize(TestPluginContext(JsonObject(emptyMap()), env))
            provider.enrich(failedMr, JsonObject(emptyMap()), EnrichmentContext.EMPTY)
            provider.shutdown()

            assertEquals(listOf("/api/v4/projects/42/merge_requests/${failedMr.iid}/pipelines"), requests.map { it.url.encodedPath })
            assertEquals("job-1", requests.single().headers["JOB-TOKEN"])
        }

    private companion object {
        const val RUNS = """{"workflow_runs":[
            {"id":11,"name":"CI","conclusion":"failure"},{"id":12,"name":"Lint","conclusion":"success"}]}"""
        const val JOBS = """{"jobs":[
            {"id":21,"name":"unit-tests","conclusion":"failure","html_url":"https://github.com/acme/app/actions/runs/11/job/21",
             "completed_at":"2026-10-01T10:00:00Z","steps":[{"name":"Checkout","conclusion":"success"},{"name":"Run tests","conclusion":"failure"}]},
            {"id":22,"name":"assemble","conclusion":"success","steps":[]}
        ]}"""
    }
}
