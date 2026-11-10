@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.enrichment.confluence

import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import io.karandash.providers.common.CachedEnrichment
import io.karandash.providers.common.EnrichmentHttp
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.enrichment.EnrichmentConfigParser
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.sdk.platform.ReinitializableClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.atomicfu.atomic
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Enrichment provider that fetches Confluence page content referenced from
 * linked Jira issues (`jira:linked-issues`) and from URLs in MR text.
 *
 * Produced enrichment ID:
 * - `confluence:pages` - `Map<pageId, ConfluencePageContent>`
 *
 * Requires auth: `CONFLUENCE_TOKEN` env var (falls back to `JIRA_TOKEN` for
 * Atlassian Cloud deployments sharing a single token). With an email (`CONFLUENCE_EMAIL`, then `JIRA_EMAIL`) it is
 * sent as Basic auth, without one as a Bearer personal access token (Server/Data Center).
 *
 * `defaultEnabled = false` - requires explicit `enrichment.confluence:` config block.
 */
class ConfluenceEnrichmentProvider internal constructor(
    private val testEngine: HttpClientEngine? = null,
) : EnrichmentProviderModule {
    constructor() : this(null)

    override val id: String = ID
    override val name: String = "Confluence Enrichment"
    override val version: String = "1.0.0"
    override val dslName: String = CONFIG_KEY
    override val configKey: String = CONFIG_KEY
    override val defaultEnabled: Boolean = false
    override val defaultPriority: Int = 100
    override val description: String =
        "Fetches Confluence page content from linked Jira issues and MR text references."

    private var logger: Logger = NoOpLogger
    private val confluenceConfig = atomic<ConfluenceConfig>(ConfluenceConfig())
    private val apiClient = ReinitializableClient<ConfluenceApiClient> { it.close() }
    private val limiter = atomic<CachedEnrichment?>(null)
    private val skip = atomic<String?>(null)

    override val providedEnrichment: Set<String> get() = setOf(EnrichmentKeys.CONFLUENCE_PAGES)

    override fun requiredEnrichment(): List<String> = listOf(EnrichmentKeys.JIRA_LINKED_ISSUES)

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        confluenceConfig.value = decodeConfig(context.pluginConfig)
        val url = confluenceConfig.value.url.ifBlank { null } ?: run {
            logger.warn("Confluence enrichment: url not configured — provider disabled")
            return false
        }
        skip.value = null
        val token = confluenceConfig.value.token
            ?: context.env("CONFLUENCE_TOKEN")
            ?: context.env("JIRA_TOKEN")
        if (token == null) {
            logger.warn("Confluence enrichment: url configured but no CONFLUENCE_TOKEN or JIRA_TOKEN — skipped")
            skip.value = "url set but neither CONFLUENCE_TOKEN nor JIRA_TOKEN set: no page content"
            apiClient.close()
            return true
        }
        val email = confluenceConfig.value.email
            ?: context.env("CONFLUENCE_EMAIL")
            ?: context.env("JIRA_EMAIL")
        val auth = EnrichmentHttp.atlassianAuth(email, token)
        apiClient.rebuild(listOf(url, auth, context.allowLocalhost)) {
            ConfluenceApiClient(EnrichmentHttp.client(context, "Confluence", url, auth, testEngine), url, logger)
        }
        limiter.value = CachedEnrichment(context, CACHE_NAME, CachedEnrichment.DEFAULT_MAX_REQUESTS_PER_MINUTE)
        logger.debug("Confluence enrichment: initialized for $url")
        return true
    }

    override fun skipReason(): String? = skip.value

    override suspend fun shutdown() {
        apiClient.close()
    }

    override suspend fun enrich(
        mr: MergeRequest,
        config: JsonElement,
        upstreamContext: EnrichmentContext,
    ): Map<String, JsonElement> {
        val client = apiClient.value ?: return emptyMap()
        if (!confluenceConfig.value.pages.enabled) return emptyMap()
        val cached = limiter.value ?: return fetchPages(mr, upstreamContext, client)
        val linked = upstreamContext.get(EnrichmentKeys.JIRA_LINKED_ISSUES)?.toString().orEmpty()
        val deps = CachedEnrichment.mrTextDependencies(mr, "apiUrl" to confluenceConfig.value.url, "linkedIssues" to linked)
        return cached.enrich(deps) { fetchPages(mr, upstreamContext, client) }
    }

    private suspend fun <T> limited(block: suspend () -> T): T {
        val cached = limiter.value ?: return block()
        return cached.request(block)
    }

    private suspend fun fetchPages(
        mr: MergeRequest,
        upstreamContext: EnrichmentContext,
        client: ConfluenceApiClient,
    ): Map<String, JsonElement> {
        val pageIds = collectPageIds(mr, upstreamContext, client)
        if (pageIds.isEmpty()) return emptyMap()

        val pages = mutableMapOf<String, JsonElement>()
        for (pageId in pageIds.take(confluenceConfig.value.pages.maxPagesPerMr)) {
            val content = limited { client.fetchPage(pageId) } ?: continue
            val truncated = content.truncateMarkdown(confluenceConfig.value.pages.maxCharsPerPage)
            pages[pageId] = KarandashJson.Default.encodeToJsonElement(
                ConfluencePageContent.serializer(),
                truncated,
            )
        }

        if (pages.isEmpty()) return emptyMap()
        logger.debug("Confluence enrichment: fetched ${pages.size} page(s)")
        return mapOf(
            EnrichmentKeys.CONFLUENCE_PAGES to KarandashJson.Default.encodeToJsonElement(
                MapSerializer(String.serializer(), JsonElement.serializer()),
                pages,
            ),
        )
    }

    private suspend fun collectPageIds(
        mr: MergeRequest,
        upstreamContext: EnrichmentContext,
        client: ConfluenceApiClient,
    ): Set<String> {
        val pageIds = mutableSetOf<String>()
        val baseUrl = confluenceConfig.value.url
        buildList {
            add(mr.title)
            mr.description?.let { add(it) }
            mr.commits.forEach { add(it.message) }
        }.forEach { text ->
            pageIds.addAll(ConfluenceUrlExtractor.extractPageIds(text, baseUrl))
            ConfluenceUrlExtractor.extractShortLinks(text, baseUrl).forEach { token ->
                limited { client.resolveShortLink(token) }?.let { pageIds.add(it) }
            }
        }
        if (!upstreamContext.isMissing(EnrichmentKeys.JIRA_LINKED_ISSUES)) {
            pageIds.addAll(extractPageIdsFromLinkedIssues(upstreamContext, baseUrl))
        }
        return pageIds
    }

    private fun decodeConfig(config: JsonElement): ConfluenceConfig =
        EnrichmentConfigParser.decodeOrDefault(config, ConfluenceConfig.serializer(), KNOWN_KEYS, ID, logger::warn) { ConfluenceConfig() }

    companion object {
        const val ID = "confluence"
        const val CONFIG_KEY = ID
        private const val CACHE_NAME = "enrichment:confluence"
        private val KNOWN_KEYS = setOf("email", "pages")
    }
}

private fun ConfluencePageContent.truncateMarkdown(maxChars: Int): ConfluencePageContent =
    if (markdown.length > maxChars) copy(markdown = markdown.take(maxChars)) else this

private fun extractPageIdsFromLinkedIssues(
    context: EnrichmentContext,
    baseUrl: String,
): List<String> {
    val json = context.get(EnrichmentKeys.JIRA_LINKED_ISSUES) as? JsonObject ?: return emptyList()
    val result = mutableListOf<String>()
    json.values.forEach { issueLinks ->
        (issueLinks as? JsonArray)?.forEach { link ->
            val remoteUrl = (link as? JsonObject)?.get("remoteUrl")?.jsonPrimitive?.content
            if (!remoteUrl.isNullOrBlank()) {
                result.addAll(ConfluenceUrlExtractor.extractPageIds(remoteUrl, baseUrl))
            }
        }
    }
    return result
}
