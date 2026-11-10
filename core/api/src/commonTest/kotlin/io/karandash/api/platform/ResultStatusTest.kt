package io.karandash.api.platform

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultStatusTest {
    @Test
    fun `status with annotations round trips through JSON`() {
        val status = ResultStatus(
            name = "karandash",
            passed = false,
            title = "1 error",
            summary = "Title too short",
            headSha = "abc123",
            annotations = listOf(
                ResultAnnotation(
                    path = "src/Main.kt",
                    line = 3,
                    endLine = 5,
                    level = ResultAnnotationLevel.FAILURE,
                    title = "titleFormat",
                    message = "Title too short",
                ),
            ),
            detailsUrl = "https://ci.example.com/run/1",
        )

        assertEquals(status, Json.decodeFromString<ResultStatus>(Json.encodeToString(status)))
    }

    @Test
    fun `minimal status decodes with defaults`() {
        val json = """{"name":"karandash","passed":true,"title":"ok","summary":"","headSha":"abc"}"""

        val status = Json.decodeFromString<ResultStatus>(json)

        assertTrue(status.passed)
        assertTrue(status.annotations.isEmpty())
        assertNull(status.detailsUrl)
    }

    @Test
    fun `annotation levels serialize by name`() {
        val annotation = ResultAnnotation(path = "a.kt", line = 1, level = ResultAnnotationLevel.NOTICE, title = "t", message = "m")

        val encoded = Json.encodeToString(annotation)

        assertTrue(encoded.contains("\"level\":\"NOTICE\""))
        assertEquals(annotation, Json.decodeFromString<ResultAnnotation>(encoded))
    }

    @Test
    fun `publisher receives the status it is given`() =
        runTest {
            val published = mutableListOf<Triple<String, String, ResultStatus>>()
            val publisher = ResultStatusPublisher { projectId, mrId, status -> published += Triple(projectId, mrId, status) }
            val status = ResultStatus(name = "karandash", passed = true, title = "ok", summary = "", headSha = "abc")

            publisher.publishResultStatus("group/project", "7", status)

            assertEquals(listOf(Triple("group/project", "7", status)), published)
        }
}
