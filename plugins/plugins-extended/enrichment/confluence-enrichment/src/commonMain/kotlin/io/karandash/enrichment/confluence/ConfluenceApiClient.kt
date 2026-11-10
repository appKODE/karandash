package io.karandash.enrichment.confluence

import io.karandash.models.support.KarandashJson
import io.karandash.providers.common.fetchOrNull
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

/**
 * HTTP client for the Confluence Cloud REST API v1.
 *
 * Authentication comes with [httpClient] (`EnrichmentHttp.atlassianAuth`: Basic email and API token, the same
 * Atlassian Cloud credential as Jira, or a Bearer personal access token). Page bodies are fetched in storage format and converted
 * to markdown via [StorageXmlToMarkdown].
 *
 * Closes the underlying Ktor [HttpClient] via [close].
 */
internal class ConfluenceApiClient(
    private val httpClient: HttpClient,
    baseUrl: String,
    private val logger: Logger = NoOpLogger,
) {
    private val cleanBase: String = baseUrl.trimEnd('/')

    /**
     * Fetches a page by numeric page ID, converting its storage-format body
     * to markdown.
     *
     * Returns `null` on 404, auth failure, network error, or parse failure
     * (each case logged at `warn`).
     */
    suspend fun fetchPage(pageId: String): ConfluencePageContent? {
        val url = "$cleanBase/rest/api/content/$pageId?expand=body.storage,version"
        return fetchOrNull("Confluence: failed to fetch page $pageId", logger::warn) {
            val response = httpClient.get(url)
            val dto = KarandashJson.Default.decodeFromString<ConfluencePageResponse>(response.bodyAsText())
            val storageXml = dto.body.storage?.value ?: ""
            val markdown = StorageXmlToMarkdown.convert(storageXml)
            val webUi = dto.links?.webui?.let { "$cleanBase$it" } ?: url
            ConfluencePageContent(
                pageId = dto.id,
                title = dto.title,
                version = dto.version.number,
                markdown = markdown,
                sourceUrl = webUi,
            )
        }
    }

    /**
     * Resolves a Confluence short-link token to a numeric page ID by following
     * the redirect from `/x/<token>` and reading the page ID from the final URL.
     *
     * Returns `null` if the short-link cannot be resolved.
     */
    suspend fun resolveShortLink(token: String): String? {
        val url = "$cleanBase/x/$token"
        return fetchOrNull("Confluence: failed to resolve short-link /x/$token", logger::warn) {
            val response = httpClient.get(url)
            ConfluenceUrlExtractor
                .extractPageIds(
                    response.call.request.url
                        .toString(),
                    cleanBase
                ).firstOrNull()
        }
    }

    fun close() = httpClient.close()
}
