@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class, ExperimentalSerializationApi::class)

package io.karandash.providers.common

import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.models.MergeRequest
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.platform.ReinitializableClient
import kotlinx.atomicfu.atomic
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject

/** The `issues:` block every issue enrichment provider reads. */
@Serializable
data class IssueSubConfig(
    val enabled: Boolean = true,
    @SerialName("max-issues-per-mr") @JsonNames("maxIssuesPerMr") val maxIssuesPerMr: Int = DEFAULT_MAX_ISSUES,
) {
    companion object {
        const val DEFAULT_MAX_ISSUES = 10
    }
}

/** An issue tracker client: one issue by number as its published JSON, null when it cannot be fetched. */
interface IssueClient {
    suspend fun fetchIssueJson(number: Int): JsonElement?

    fun close()
}

/**
 * The lifecycle shared by the issue enrichment providers: decode the config, resolve credentials and target or skip
 * with a reason, extract issue numbers from the MR title, description and commits, fetch them rate-limited and cached,
 * and publish them under [enrichmentKey] keyed by number.
 *
 * @param label log prefix, e.g. `GitHub issue enrichment`
 */
abstract class AbstractIssueEnrichmentProvider(
    private val label: String,
    private val enrichmentKey: String,
) : EnrichmentProviderModule {
    protected var logger: Logger = NoOpLogger
        private set
    private val settings = atomic(IssueSubConfig())
    private val apiClient = ReinitializableClient<IssueClient> { it.close() }
    private val limiter = atomic<CachedEnrichment?>(null)
    private val skip = atomic<String?>(null)

    override val providedEnrichment: Set<String> get() = setOf(enrichmentKey)

    override fun requiredEnrichment(): List<String> = emptyList()

    /** Decodes and keeps the provider's config; its `issues:` block, or null when it does not decode (a bad config). */
    protected abstract fun decode(config: JsonElement): IssueSubConfig?

    /** Resolves credentials and target and calls [connectClient]; returns a skip reason when one is missing. */
    protected abstract suspend fun connect(context: PluginContext): String?

    protected abstract fun extract(texts: List<String>): Collection<Int>

    protected open fun cacheDependencies(mr: MergeRequest): Map<String, String> = CachedEnrichment.mrTextDependencies(mr)

    /** Builds the client unless the one for [key] is already up. */
    protected suspend fun connectClient(
        key: Any,
        build: () -> IssueClient,
    ) {
        apiClient.rebuild(key, build)
    }

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        // a config that does not decode is a bad config (false); missing credentials or project only skip
        settings.value = decode(context.pluginConfig) ?: return false
        if (!settings.value.enabled) {
            logger.debug("$label: disabled via config")
            return false
        }
        skip.value = null
        val reason = connect(context)
        if (reason != null) {
            logger.warn("$label: $reason")
            skip.value = reason
            apiClient.close()
            return true
        }
        limiter.value = CachedEnrichment(context, "enrichment:$id", CachedEnrichment.DEFAULT_MAX_REQUESTS_PER_MINUTE)
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
        val cached = limiter.value ?: return fetchIssues(mr, client)
        return cached.enrich(cacheDependencies(mr)) { fetchIssues(mr, client) }
    }

    private suspend fun fetchIssues(
        mr: MergeRequest,
        client: IssueClient,
    ): Map<String, JsonElement> {
        val texts = buildList {
            add(mr.title)
            mr.description?.let { add(it) }
            mr.commits.forEach { add(it.message) }
        }
        val numbers = extract(texts)
        if (numbers.isEmpty()) return emptyMap()

        val issues = mutableMapOf<String, JsonElement>()
        for (number in numbers.take(settings.value.maxIssuesPerMr)) {
            issues[number.toString()] = limiter.value?.request { client.fetchIssueJson(number) } ?: continue
        }

        if (issues.isEmpty()) return emptyMap()
        logger.debug("$label: fetched ${issues.size} issue(s)")
        return mapOf(enrichmentKey to JsonObject(issues))
    }
}
