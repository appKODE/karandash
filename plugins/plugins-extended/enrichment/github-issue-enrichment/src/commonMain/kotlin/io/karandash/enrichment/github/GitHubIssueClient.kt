package io.karandash.enrichment.github

import io.karandash.models.support.KarandashJson
import io.karandash.providers.common.IssueClient
import io.karandash.providers.common.fetchOrNull
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonElement

internal class GitHubIssueClient(
    private val httpClient: HttpClient,
    baseUrl: String,
    private val owner: String,
    private val repo: String,
    private val logger: Logger = NoOpLogger,
) : IssueClient {
    private val cleanBase: String = baseUrl.trimEnd('/')

    override suspend fun fetchIssueJson(number: Int): JsonElement? =
        fetchIssue(number)?.let { KarandashJson.Default.encodeToJsonElement(GitHubIssueDetails.serializer(), it) }

    suspend fun fetchIssue(number: Int): GitHubIssueDetails? {
        val url = "$cleanBase/repos/$owner/$repo/issues/$number"
        return fetchOrNull("GitHub: failed to fetch issue #$number", logger::warn) {
            val response = httpClient.get(url)
            val dto = KarandashJson.Default.decodeFromString<GitHubIssueResponse>(response.bodyAsText())
            GitHubIssueDetails(
                number = dto.number,
                title = dto.title,
                body = dto.body,
                state = dto.state,
                labels = dto.labels.map { it.name },
                milestone = dto.milestone?.title,
                assignees = dto.assignees.map { it.login },
            )
        }
    }

    override fun close() = httpClient.close()
}
