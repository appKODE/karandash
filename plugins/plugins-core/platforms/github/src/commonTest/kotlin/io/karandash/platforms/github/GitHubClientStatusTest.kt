package io.karandash.platforms.github

import io.karandash.api.exception.ProviderApiException
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GitHubClientStatusTest {
    private fun client(status: HttpStatusCode) =
        GitHubClient(
            httpClient = HttpClient(
                MockEngine {
                    respond(content = "boom", status = status)
                }
            ) { install(ContentNegotiation) { json(KarandashJson.Default) } },
            responseCache = null
        )

    @Test
    fun `void mutator throws on failing status`() =
        runTest { assertFailsWith<ProviderApiException> { client(HttpStatusCode.Forbidden).addLabels("o", "r", 1, listOf("bug")) } }

    @Test
    fun `body-returning mutator throws on failing status`() =
        runTest { assertFailsWith<ProviderApiException> { client(HttpStatusCode.NotFound).postIssueComment("o", "r", 1, "hi") } }
}
