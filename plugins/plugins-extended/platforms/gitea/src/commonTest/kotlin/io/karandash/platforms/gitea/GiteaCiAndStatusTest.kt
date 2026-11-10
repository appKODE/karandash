package io.karandash.platforms.gitea

import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.ResultStatus
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GiteaCiAndStatusTest {
    private fun client(engine: MockEngine) =
        GiteaClient(
            baseUrl = "https://gitea.example.com",
            token = "t",
            httpClient = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } },
        )

    @Test
    fun `a commit status posts the verdict context and details link to the head commit`() =
        runTest {
            val seen = mutableListOf<Pair<String, String>>()
            val client = client(
                MockEngine { request ->
                    seen += request.url.encodedPath to (request.body as TextContent).text
                    respond("{}", HttpStatusCode.Created)
                },
            )

            client.publishCommitStatus("o w", "r", ResultStatus("karandash", false, "2 errors", "s", "abc/1", detailsUrl = "https://d"))
            client.publishCommitStatus("o", "r", ResultStatus("karandash", true, "ok", "s", "def"))

            assertEquals("/api/v1/repos/o%20w/r/statuses/abc%2F1", seen[0].first)
            assertEquals(
                buildJsonObject {
                    put("state", "failure")
                    put("context", "karandash")
                    put("description", "2 errors")
                    put("target_url", "https://d")
                },
                KarandashJson.Default.parseToJsonElement(seen[0].second),
            )
            assertEquals("/api/v1/repos/o/r/statuses/def", seen[1].first)
            assertEquals(JsonPrimitive("success"), (KarandashJson.Default.parseToJsonElement(seen[1].second) as JsonObject)["state"])
            assertNull((KarandashJson.Default.parseToJsonElement(seen[1].second) as JsonObject)["target_url"])
        }

    @Test
    fun `actions builds are read from the github variables`() {
        val env = mapOf(
            "GITEA_ACTIONS" to "true",
            "GITHUB_REPOSITORY" to "o/r",
            "GITHUB_REF" to "refs/pull/12/merge",
            "GITHUB_TOKEN" to "gh",
            "GITHUB_SERVER_URL" to "https://git.example",
        )

        assertEquals(CIEnvironment("gitea", "o/r", "12", "gh", "https://git.example"), GiteaCredentials.detectCi("gitea", env::get))
        assertNull(GiteaCredentials.detectCi("gitea", (env + ("GITHUB_REF" to "refs/heads/main"))::get)?.mrId)
    }

    @Test
    fun `a job exporting the gitea variables is a build and a bare environment is not`() {
        val env = mapOf(
            "GITEA_SERVER_URL" to "https://git.example",
            "GITEA_REPO_OWNER" to "o",
            "GITEA_REPO_NAME" to "r",
            "GITEA_PR_ID" to "5",
            "GITEA_TOKEN" to "tok",
        )

        assertEquals(CIEnvironment("gitea", "o/r", "5", "tok", "https://git.example"), GiteaCredentials.detectCi("gitea", env::get))
        assertNull(GiteaCredentials.detectCi("gitea") { null })
    }

    @Test
    fun `credentials resolve the server and token under actions`() {
        val env = mapOf("FORGEJO_ACTIONS" to "true", "GITHUB_SERVER_URL" to "https://forge.example/", "GITHUB_TOKEN" to "gh")

        val resolved = GiteaCredentials.resolve(JsonObject(emptyMap()), env::get)

        assertEquals(GiteaCredentials.Resolved("https://forge.example", "gh"), resolved)
        assertNull(GiteaCredentials.resolve(JsonObject(emptyMap())) { null })
        assertEquals(
            setOf("GITEA_URL", "GITEA_SERVER_URL", "GITEA_TOKEN", "GITHUB_SERVER_URL", "GITHUB_TOKEN"),
            GiteaCredentials.supportedEnvVars()
        )
    }
}
