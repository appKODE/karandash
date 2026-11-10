package io.karandash.platforms.github

import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.ResultAnnotation
import io.karandash.api.platform.ResultAnnotationLevel
import io.karandash.api.platform.ResultStatus
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitHubCheckRunTest {
    /** Not @Serializable: test double, holds a non-serializable `HttpMethod`. */
    private data class Captured(
        val method: HttpMethod,
        val path: String,
        val body: JsonObject,
    )

    private suspend fun serviceWith(
        captured: MutableList<Captured>,
        response: String = """{"id": 42}""",
    ): GitHubPlatformService {
        val engine = MockEngine { request ->
            val text = (request.body as? TextContent)?.text ?: "{}"
            captured += Captured(request.method, request.url.encodedPath, Json.parseToJsonElement(text).jsonObject)
            respond(response, HttpStatusCode.OK)
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = GitHubPlatformService()
        service.client.rebuild("test") {
            GitHubClient(baseUrl = "https://api.github.test", httpClient = http, logger = NoOpLogger, responseCache = null)
        }
        return service
    }

    private fun annotation(
        line: Int,
        level: ResultAnnotationLevel = ResultAnnotationLevel.WARNING,
        endLine: Int? = null,
    ) = ResultAnnotation("src/A.kt", line, endLine, level, "title $line", "message $line")

    private fun status(
        passed: Boolean = false,
        annotations: List<ResultAnnotation> = emptyList(),
        detailsUrl: String? = null,
    ) = ResultStatus("karandash", passed, "3 findings", "summary", "abc123", annotations, detailsUrl)

    @Test
    fun `a failed run is published as one failure check run with its annotation levels`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(captured)
            val annotations =
                listOf(
                    annotation(1, ResultAnnotationLevel.NOTICE),
                    annotation(2, ResultAnnotationLevel.WARNING, endLine = 1),
                    annotation(3, ResultAnnotationLevel.FAILURE, endLine = 5),
                )

            service.publishResultStatus("o/r", "7", status(annotations = annotations, detailsUrl = "https://ci/report"))

            val call = captured.single()
            assertEquals(HttpMethod.Post, call.method)
            assertEquals("/repos/o/r/check-runs", call.path)
            assertEquals(
                "failure",
                call.body
                    .getValue("conclusion")
                    .jsonPrimitive.content
            )
            assertEquals(
                "abc123",
                call.body
                    .getValue("head_sha")
                    .jsonPrimitive.content
            )
            assertEquals(
                "https://ci/report",
                call.body
                    .getValue("details_url")
                    .jsonPrimitive.content
            )
            val sent = call.body
                .getValue("output")
                .jsonObject
                .getValue("annotations")
                .jsonArray
                .map { it.jsonObject }
            assertEquals(listOf("notice", "warning", "failure"), sent.map { it.getValue("annotation_level").jsonPrimitive.content })
            assertEquals(listOf("1", "2", "5"), sent.map { it.getValue("end_line").jsonPrimitive.content })
        }

    @Test
    fun `a passing run with no annotations is a success check run`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(captured)

            service.publishResultStatus("o/r", "7", status(passed = true))

            val call = captured.single()
            assertEquals(
                "success",
                call.body
                    .getValue("conclusion")
                    .jsonPrimitive.content
            )
            assertEquals(
                JsonArray(emptyList()),
                call.body
                    .getValue("output")
                    .jsonObject
                    .getValue("annotations")
            )
        }

    @Test
    fun `annotations beyond fifty are appended by patches to the created run`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(captured)

            service.publishResultStatus("o/r", "7", status(annotations = (1..120).map { annotation(it) }))

            assertEquals(listOf(HttpMethod.Post, HttpMethod.Patch, HttpMethod.Patch), captured.map { it.method })
            assertEquals(listOf("/repos/o/r/check-runs", "/repos/o/r/check-runs/42", "/repos/o/r/check-runs/42"), captured.map { it.path })
            val sizes = captured.map {
                it.body
                    .getValue("output")
                    .jsonObject
                    .getValue("annotations")
                    .jsonArray.size
            }
            assertEquals(listOf(CHECK_RUN_ANNOTATIONS_PER_REQUEST, CHECK_RUN_ANNOTATIONS_PER_REQUEST, 20), sizes)
        }

    @Test
    fun `a created run without an id cannot be appended to`() =
        runTest {
            val service = serviceWith(mutableListOf(), response = "{}")

            val error =
                assertFailsWith<IllegalStateException> {
                    service.publishResultStatus(
                        "o/r",
                        "7",
                        status(annotations = (1..51).map { annotation(it) })
                    )
                }

            assertTrue(error.message.orEmpty().contains("without an id"), error.message)
        }

    @Test
    fun `result status is a supported operation`() =
        runTest {
            assertTrue(serviceWith(mutableListOf()).supports(PlatformOperation.RESULT_STATUS))
        }
}
