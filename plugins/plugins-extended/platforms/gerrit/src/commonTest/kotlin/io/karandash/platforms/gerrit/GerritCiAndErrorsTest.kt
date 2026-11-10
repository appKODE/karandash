package io.karandash.platforms.gerrit

import io.karandash.api.exception.ProviderApiException
import io.karandash.api.platform.CIEnvironment
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GerritCiAndErrorsTest {
    private fun root(vararg env: Pair<String, String>) = GerritCredentials.ciBaseUrl(mapOf(*env)::get)

    @Test
    fun `the web root comes from the url the change url or the host`() {
        assertEquals("https://g.example", root("GERRIT_URL" to "https://g.example"))
        assertEquals("https://g.example/r", root("GERRIT_CHANGE_URL" to "https://g.example/r/c/team/app/+/42"))
        assertEquals("https://g.example", root("GERRIT_CHANGE_URL" to "https://g.example/42"))
        assertEquals("http://h", root("GERRIT_HOST" to "h", "GERRIT_SCHEME" to "http"))
        assertEquals("https://h", root("GERRIT_HOST" to "h", "GERRIT_SCHEME" to "ssh"))
        assertEquals("https://h", root("GERRIT_CHANGE_URL" to "not-a-url", "GERRIT_HOST" to "h"))
        assertNull(root())
    }

    @Test
    fun `a trigger build carries the project change and token and a bare environment is not a build`() {
        val env = mapOf(
            "GERRIT_URL" to "https://g.example",
            "GERRIT_PROJECT" to "team/app",
            "GERRIT_CHANGE_NUMBER" to "42",
            "GERRIT_TOKEN" to "tok",
        )

        assertEquals(CIEnvironment("gerrit", "team/app", "42", "tok", "https://g.example"), GerritCredentials.detectCi("gerrit", env::get))
        assertNull(GerritCredentials.detectCi("gerrit") { null })
    }

    @Test
    fun `credentials resolve from the build environment`() {
        val env = mapOf("GERRIT_HOST" to "g.example", "GERRIT_USERNAME" to "bot", "GERRIT_TOKEN" to "tok")

        assertEquals(
            GerritCredentials.Resolved("https://g.example", "bot", "tok"),
            GerritCredentials.resolve(JsonObject(emptyMap()), env::get),
        )
        assertNull(GerritCredentials.resolve(JsonObject(emptyMap())) { null })
        assertTrue("GERRIT_HOST" in GerritCredentials.supportedEnvVars())
    }

    @Test
    fun `a failing change search surfaces the status as a provider error`() =
        runTest {
            val client = GerritProviderClient(
                baseUrl = "https://g.example",
                httpClient = HttpClient(MockEngine { respond("denied", HttpStatusCode.Forbidden) }),
            )

            val error = assertFailsWith<ProviderApiException> { client.searchChanges("team/app", "fix", null, 5) }

            assertEquals(403, error.statusCode)
        }

    @Test
    fun `file content that is not base64 is skipped with a warning`() =
        runTest {
            val context = TestPluginContext()
            val client = GerritClient(
                baseUrl = "https://g.example",
                httpClient = HttpClient(MockEngine { respond("!!not base64!!", HttpStatusCode.OK) }),
                logger = context.logger,
            )

            assertNull(client.fetchFileContent("team/app", "main", "a.kt"))
            assertTrue(context.warnings.single().contains("not base64"), context.warnings.toString())
        }

    @Test
    fun `a vote lookup that fails after the account is known is null with a warning`() =
        runTest {
            val context = TestPluginContext()
            val client = GerritClient(
                baseUrl = "https://g.example",
                httpClient = HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("/accounts/self")) {
                            respond(")]}'\n{\"_account_id\":7,\"username\":\"bot\"}", HttpStatusCode.OK)
                        } else {
                            respond("boom", HttpStatusCode.InternalServerError)
                        }
                    },
                ),
                logger = context.logger,
            )

            assertNull(client.getCurrentCodeReviewVote("team/app", "42"))
            assertTrue(context.warnings.single().contains("getCurrentCodeReviewVote failed"), context.warnings.toString())
        }
}
