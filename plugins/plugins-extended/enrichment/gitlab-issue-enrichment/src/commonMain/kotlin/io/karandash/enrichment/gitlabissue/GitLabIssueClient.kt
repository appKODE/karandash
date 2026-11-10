package io.karandash.enrichment.gitlabissue

import io.karandash.http.PathSegmentEncoder
import io.karandash.models.support.KarandashJson
import io.karandash.providers.common.IssueClient
import io.karandash.providers.common.fetchOrNull
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonElement

internal class GitLabIssueClient(
    private val httpClient: HttpClient,
    baseUrl: String,
    private val projectId: String,
    private val logger: Logger = NoOpLogger,
) : IssueClient {
    private val cleanBase: String = baseUrl.trimEnd('/')

    override suspend fun fetchIssueJson(number: Int): JsonElement? =
        fetchIssue(number)?.let { KarandashJson.Default.encodeToJsonElement(GitLabIssueDetails.serializer(), it) }

    suspend fun fetchIssue(iid: Int): GitLabIssueDetails? {
        val url = "$cleanBase/api/v4/projects/${PathSegmentEncoder.encode(projectId)}/issues/$iid"
        return fetchOrNull("GitLab: failed to fetch issue #$iid", logger::warn) {
            val response = httpClient.get(url)
            val dto = KarandashJson.Default.decodeFromString<GitLabIssueResponse>(response.bodyAsText())
            GitLabIssueDetails(
                iid = dto.iid,
                title = dto.title,
                description = dto.description,
                state = dto.state,
                labels = dto.labels,
                milestone = dto.milestone?.title,
                assignees = dto.assignees.map { it.username },
            )
        }
    }

    override fun close() = httpClient.close()
}
