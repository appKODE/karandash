package io.karandash.platforms.gerrit

import io.karandash.api.exception.ProviderApiException
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.support.KarandashJson
import io.karandash.pagination.PaginationResult
import io.karandash.pagination.paginateByCursor
import io.karandash.providers.common.UrlValidator
import io.karandash.providers.common.jsonListIds
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPath
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Client for interacting with Gerrit REST API.
 *
 * Does not own [httpClient]: whoever passes it in closes it (the platform service on replace/shutdown).
 *
 * @param httpClient must be built by [ProviderHttpClientFactory]: the private-network check that `baseUrl` relies on
 *   (`allowPrivateNetwork = true` at construction) runs in its ssrfRedirectValidationPlugin, so an arbitrary client bypasses SSRF.
 */
class GerritProviderClient(
    baseUrl: String,
    private val httpClient: HttpClient,
    private val token: String? = null,
    private val json: Json = KarandashJson.Lenient, // decode-only: encodeDefaults has no effect
    allowLocalhost: Boolean = false,
    private val username: String? = null,
    private val logger: Logger = NoOpLogger,
) {
    // private hosts are checked per request against the run's NetworkPolicy (ssrfRedirectValidationPlugin)
    private val baseUrl: String = UrlValidator.validateBaseUrl(baseUrl, allowLocalhost = allowLocalhost, allowPrivateNetwork = true)

    /** Gerrit serves authenticated REST calls under `/a/` (HTTP Basic `username:http-password`); anonymous ones without it. */
    private val apiUrl: String = if (username != null && token != null) "${this.baseUrl}/a" else this.baseUrl

    private fun HttpRequestBuilder.configureAuth() {
        if (username != null && token != null) basicAuth(username, token)
    }

    /**
     * Fetch a change by project and change number
     * @param project The project name (URL-encoded)
     * @param changeNumber The change number
     * @return The Gerrit change with details
     */
    suspend fun fetchChange(
        project: String,
        changeNumber: String
    ): GerritChange {
        // Gerrit wants a slash in a project name as %2F, which encodeURLPath leaves as is.
        val encodedProject = project.encodeURLParameter()
        val url = "$apiUrl/changes/$encodedProject~$changeNumber" +
            "?o=CURRENT_REVISION&o=CURRENT_COMMIT&o=CURRENT_FILES" +
            "&o=LABELS&o=DETAILED_LABELS&o=DETAILED_ACCOUNTS&o=REVIEWER_UPDATES"

        val response = httpClient.get(url) { configureAuth() }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw ProviderApiException(
                statusCode = response.status.value,
                statusDescription = response.status.description,
                platformName = "Gerrit",
                requestLabel = "GET /changes/$encodedProject~$changeNumber",
                responseBody = body.take(MAX_ERROR_BODY),
            )
        }

        // Gerrit returns ")]}'" prefix to prevent XSSI attacks
        return json.decodeFromString(stripGerritPrefix(body))
    }

    /**
     * Numbers of the open changes of [project] on [branch], most recently updated first, at most [limit]. Pages with
     * `S` while the last change of a page carries `_more_changes` (the server's own query limit can cut a page short).
     */
    suspend fun listOpenChangeNumbers(
        project: String,
        branch: String,
        limit: Int,
    ): List<String> {
        val query = "status:open project:$project branch:$branch".encodeURLParameter()
        val truncated: (Int) -> Unit = { cap ->
            logger.warn("Gerrit open changes of $project: stopped after $cap pages (http.pagination.maxPages)")
        }
        val numbers = paginateByCursor(onTruncated = truncated) { cursor ->
            val start = cursor?.toInt() ?: 0
            val page = queryChanges(query, limit - start, start = start)
            val pageNumbers = jsonListIds(page, "_number")
            val more = jsonListIds(page, "_number") { it["_more_changes"]?.jsonPrimitive?.booleanOrNull == true }.isNotEmpty()
            val next = start + pageNumbers.size
            PaginationResult(pageNumbers, next.toString().takeIf { more && pageNumbers.isNotEmpty() && next < limit })
        }
        return numbers.take(limit)
    }

    /**
     * Changes of [project] in any state whose commit message matches [text] and/or owned by [author], most recently
     * updated first, at most [limit] (`GET /changes/?q=project:P message:T owner:A&o=DETAILED_ACCOUNTS`).
     */
    suspend fun searchChanges(
        project: String,
        text: String?,
        author: String?,
        limit: Int,
    ): List<GerritChange> {
        val terms = listOfNotNull(
            "project:\"$project\"",
            text?.takeIf { it.isNotBlank() }?.let { "message:\"${it.replace("\"", "")}\"" },
            author?.takeIf { it.isNotBlank() }?.let { "owner:\"${it.replace("\"", "")}\"" },
        )
        val body = queryChanges(terms.joinToString(" ").encodeURLParameter(), limit, start = 0, options = "&o=DETAILED_ACCOUNTS")
        return json.decodeFromString<List<GerritChange>>(body).take(limit)
    }

    private suspend fun queryChanges(
        query: String,
        limit: Int,
        start: Int,
        options: String = "",
    ): String {
        val response = httpClient.get("$apiUrl/changes/?q=$query&n=$limit&S=$start$options") { configureAuth() }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw ProviderApiException(
                statusCode = response.status.value,
                statusDescription = response.status.description,
                platformName = "Gerrit",
                requestLabel = "GET /changes/?q=",
                responseBody = body.take(MAX_ERROR_BODY),
            )
        }
        return stripGerritPrefix(body)
    }

    /**
     * The revision's patch as Gerrit serves it (base64 of the `git format-patch` text), or null when Gerrit
     * does not answer with one.
     */
    suspend fun fetchPatch(
        project: String,
        changeNumber: String,
        revision: String
    ): String? {
        val url = "$apiUrl/changes/${project.encodeURLParameter()}~$changeNumber/revisions/${revision.encodeURLPath()}/patch"
        val response = httpClient.get(url) { configureAuth() }
        return response.bodyAsText().takeIf { response.status.isSuccess() }
    }

    private companion object {
        const val MAX_ERROR_BODY = 300
    }
}
