package io.karandash.enrichment.jira

import io.karandash.models.support.KarandashJson
import io.karandash.providers.common.fetchOrNull
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.jsonObject

/**
 * HTTP client for the Jira Cloud REST API v3.
 *
 * Authentication comes with [httpClient] (`EnrichmentHttp.atlassianAuth`: Basic email and API token, or a
 * Bearer personal access token). All URLs are constructed from the pre-validated [baseUrl]; no
 * user-controlled values are interpolated into URL paths (ticket keys match
 * `[A-Z]{2,10}-[0-9]+` and are safe in path segments).
 *
 * Closes the underlying Ktor [HttpClient] via [close].
 */
internal class JiraApiClient(
    private val httpClient: HttpClient,
    baseUrl: String,
    private val logger: Logger = NoOpLogger,
) {
    private val cleanBase: String = baseUrl.trimEnd('/')

    /**
     * Fetches a single Jira issue by key.
     *
     * Returns `null` when the issue is not found, authentication fails,
     * or a network/parse error occurs (each case is logged at `warn`).
     */
    suspend fun fetchIssue(
        key: String,
        acceptanceField: String? = null,
    ): JiraIssueResponse? {
        val extra = acceptanceField?.takeIf { FIELD_ID_REGEX.matches(it) }
        val url = "$cleanBase/rest/api/3/issue/$key" +
            "?fields=summary,description,issuetype,priority,status," +
            "labels,assignee,customfield_10014,issuelinks" +
            (extra?.let { ",$it" } ?: "")
        return fetchOrNull("Jira: failed to fetch issue $key", logger::warn) {
            val body = httpClient
                .get(url)
                .bodyAsText()
            val issue = KarandashJson.Default.decodeFromString<JiraIssueResponse>(body)
            if (extra == null) {
                issue
            } else {
                val fields = KarandashJson.Default
                    .parseToJsonElement(body)
                    .jsonObject["fields"]
                    ?.jsonObject
                issue.copy(acceptanceField = fields?.get(extra))
            }
        }
    }

    /**
     * Fetches the remote links (external URLs such as Confluence pages) attached to an issue.
     *
     * Returns an empty list when the call fails or the response is not a link array (logged at `warn`).
     */
    suspend fun fetchRemoteLinks(key: String): List<JiraRemoteLinkObject> =
        fetchOrNull("Jira: failed to fetch remote links for $key", logger::warn) {
            val body = httpClient.get("$cleanBase/rest/api/3/issue/$key/remotelink").bodyAsText()
            KarandashJson.Default.decodeFromString<List<JiraRemoteLink>>(body).mapNotNull { it.`object` }
        }.orEmpty()

    fun close() = httpClient.close()

    private companion object {
        /** Jira field ids (`customfield_10100`); anything else is not put into the query string. */
        val FIELD_ID_REGEX = Regex("[A-Za-z0-9_]{1,64}")
    }
}
