package io.karandash.providers

import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.ResultAnnotation
import io.karandash.api.platform.ResultAnnotationLevel
import io.karandash.api.platform.ResultStatus
import io.karandash.api.platform.ResultStatusPublisher
import io.karandash.platforms.azuredevops.AzureDevOpsPlatformService
import io.karandash.platforms.bitbucket.BitbucketPlatformService
import io.karandash.platforms.gitea.GiteaPlatformService
import io.karandash.platforms.github.GitHubPlatformService
import io.karandash.platforms.gitlab.GitLabPlatformService
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each [ResultStatusPublisher] sends the platform's own result-status calls, batched to the API's limits. */
class ResultStatusPublisherTest {
    private class Call(
        val method: String,
        val path: String,
        val body: JsonElement,
    )

    @Test
    fun `GitHub creates one completed check run and appends annotations past 50 with PATCH`() =
        runTest {
            val calls = publish({ GitHubPlatformService(it) }, "team/app", status(passed = false, annotations = 120))
            assertEquals(listOf("POST", "PATCH", "PATCH"), calls.map { it.method })
            assertEquals("/repos/team/app/check-runs", calls[0].path)
            assertEquals("/repos/team/app/check-runs/42", calls[1].path)
            val created = calls[0].body.jsonObject
            assertEquals("failure", created.string("conclusion"))
            assertEquals("completed", created.string("status"))
            assertEquals("abc123", created.string("head_sha"))
            assertEquals(
                listOf(50, 50, 20),
                calls.map {
                    it.body.jsonObject
                        .annotations()
                        .size
                }
            )
            val first = calls[0]
                .body.jsonObject
                .annotations()
                .first()
                .jsonObject
            assertEquals("failure", first.string("annotation_level"))
            assertEquals("src/A.kt", first.string("path"))
        }

    @Test
    fun `GitLab posts one commit status on the head commit`() =
        runTest {
            val calls = publish({ GitLabPlatformService(it) }, "team/app", status(passed = true, annotations = 3))
            assertEquals(1, calls.size)
            assertEquals("POST", calls[0].method)
            assertEquals("/api/v4/projects/team%2Fapp/statuses/abc123", calls[0].path)
            assertEquals("success", calls[0].body.jsonObject.string("state"))
            assertEquals("karandash", calls[0].body.jsonObject.string("name"))
        }

    @Test
    fun `Gitea posts one commit status on the head commit`() =
        runTest {
            val calls = publish({ GiteaPlatformService(it) }, "team/app", status(passed = false, annotations = 3))
            assertEquals(1, calls.size)
            assertEquals("POST", calls[0].method)
            assertEquals("/api/v1/repos/team/app/statuses/abc123", calls[0].path)
            assertEquals("failure", calls[0].body.jsonObject.string("state"))
            assertEquals("karandash", calls[0].body.jsonObject.string("context"))
        }

    @Test
    fun `Bitbucket puts a Code Insights report then its annotations 100 at a time`() =
        runTest {
            val calls = publish({ BitbucketPlatformService(it) }, "team/app", status(passed = false, annotations = 150))
            assertEquals(listOf("PUT", "POST", "POST"), calls.map { it.method })
            assertTrue(calls[0].path.endsWith("/repositories/team/app/commit/abc123/reports/karandash-karandash"), calls[0].path)
            assertTrue(calls[1].path.endsWith("/reports/karandash-karandash/annotations"), calls[1].path)
            assertEquals("FAILED", calls[0].body.jsonObject.string("result"))
            assertEquals(listOf(100, 50), calls.drop(1).map { it.body.jsonArray.size })
            val first = calls[1]
                .body.jsonArray
                .first()
                .jsonObject
            assertEquals("HIGH", first.string("severity"))
            assertEquals("karandash-karandash-1", first.string("external_id"))
        }

    @Test
    fun `Azure DevOps posts one pull request status`() =
        runTest {
            val calls = publish({ AzureDevOpsPlatformService(it) }, "org/proj/repo", status(passed = true, annotations = 0))
            assertEquals(1, calls.size)
            assertEquals("/org/proj/_apis/git/repositories/repo/pullRequests/7/statuses", calls[0].path)
            val body = calls[0].body.jsonObject
            assertEquals("succeeded", body.string("state"))
            assertEquals("karandash", body["context"]?.jsonObject?.string("genre"))
        }

    private suspend fun publish(
        create: (HttpClientEngine) -> PlatformServiceModule,
        projectId: String,
        status: ResultStatus,
    ): List<Call> {
        val calls = mutableListOf<Call>()
        val module = create(
            MockEngine { request ->
                val text = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                calls += Call(request.method.value, request.url.encodedPath, Json.parseToJsonElement(text.ifEmpty { "{}" }))
                respond("""{"id": 42}""", HttpStatusCode.Created)
            },
        )
        assertTrue(module.initialize(context()), "${module.id}: initialize")
        (module as ResultStatusPublisher).publishResultStatus(projectId, "7", status)
        module.shutdown()
        return calls
    }

    private fun status(
        passed: Boolean,
        annotations: Int,
    ) = ResultStatus(
        name = "karandash",
        passed = passed,
        title = "1 error",
        summary = "details",
        headSha = "abc123",
        annotations = List(
            annotations
        ) { ResultAnnotation("src/A.kt", it + 1, null, ResultAnnotationLevel.FAILURE, "rule", "message $it") },
    )

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.annotations(): JsonArray =
        checkNotNull(this["output"]).jsonObject["annotations"]?.jsonArray ?: JsonArray(emptyList())

    private fun context() =
        TestPluginContext(JsonObject(mapOf("url" to JsonPrimitive("https://scm.example.com"), "token" to JsonPrimitive("t0ken"))))
}
