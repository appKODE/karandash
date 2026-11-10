package io.karandash.platforms.gitlab

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** a token taken from CI_JOB_TOKEN is sent as `JOB-TOKEN`, not `PRIVATE-TOKEN`. */
class GitLabJobTokenTest {
    private val mrJson =
        """{"id": 1, "iid": 1, "title": "T", "description": null, "source_branch": "f", "target_branch": "main",
           "author": {"id": 1, "username": "u", "name": "U"}, "state": "opened", "labels": []}"""

    @Test
    fun `only a CI_JOB_TOKEN fallback is marked as a job token`() {
        val empty = JsonObject(emptyMap())
        assertTrue(GitLabCredentials.resolve(empty, mapOf("CI_JOB_TOKEN" to "job")::get).jobToken)
        assertFalse(GitLabCredentials.resolve(empty, mapOf("GITLAB_TOKEN" to "pat", "CI_JOB_TOKEN" to "job")::get).jobToken)
        val cfg = JsonObject(mapOf("token" to JsonPrimitive("cfg")))
        val resolved = GitLabCredentials.resolve(cfg, mapOf("CI_JOB_TOKEN" to "job")::get)
        assertEquals("cfg", resolved.token)
        assertFalse(resolved.jobToken)
    }

    @Test
    fun `a job token goes in the JOB-TOKEN header`() =
        runTest {
            assertEquals("job" to null, headersSent(jobToken = true).let { it["JOB-TOKEN"] to it["PRIVATE-TOKEN"] })
            assertEquals(null to "job", headersSent(jobToken = false).let { it["JOB-TOKEN"] to it["PRIVATE-TOKEN"] })
        }

    private suspend fun headersSent(jobToken: Boolean): io.ktor.http.Headers {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(mrJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = GitLabClient(baseUrl = "https://gitlab.example.com", token = "job", jobToken = jobToken, engine = engine)
        client.fetchMergeRequest("g/p", 1)
        client.close()
        assertNull(requests.singleOrNull()?.headers?.get("Authorization"))
        return requests.single().headers
    }
}
