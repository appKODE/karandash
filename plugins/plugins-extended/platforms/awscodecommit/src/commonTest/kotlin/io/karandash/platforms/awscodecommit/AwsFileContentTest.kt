package io.karandash.platforms.awscodecommit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AwsFileContentTest {
    private fun client(
        status: HttpStatusCode,
        body: String,
    ) = AwsCodeSuiteClient(httpClient = HttpClient(MockEngine { respond(body, status) }))

    @Test
    fun `fetchFileContent decodes GetFile content`() =
        runTest {
            val client = client(HttpStatusCode.OK, """{"fileContent":"bGluZTEK","blobId":"b"}""")
            assertEquals("line1\n", client.fetchFileContent("repo", "main", "src/a.kt"))
        }

    @Test
    fun `fetchFileContent is null for a missing file`() =
        runTest {
            val client = client(HttpStatusCode.BadRequest, """{"__type":"FileDoesNotExistException"}""")
            assertNull(client.fetchFileContent("repo", "main", "gone.kt"))
        }
}
