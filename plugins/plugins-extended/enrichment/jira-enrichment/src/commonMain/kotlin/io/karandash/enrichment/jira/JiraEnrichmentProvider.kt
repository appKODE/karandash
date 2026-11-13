@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.enrichment.jira

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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.TimeSource

/**
 * Enrichment provider that extracts JIRA ticket keys and, when configured with
 * a Jira URL and auth token, fetches full ticket details, acceptance criteria,
 * and linked issues.
 *
 * Enrichment IDs produced (in order):
 * - `jira:ticket-keys` - regex-scraped keys, no API required (backward-compat)
 * - `jira:tickets` - full ticket details via REST API v3
 * - `jira:acceptance-criteria` - AC bullets parsed from ticket description
 * - `jira:linked-issues` - linked issue relationships
 *
 * Degrades gracefully: when URL or auth token is absent, only `jira:ticket-keys`
 * is emitted and a `warn` log is written.
 */
class JiraEnrichmentProvider internal constructor(
    private val testEngine: HttpClientEngine? = null,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : EnrichmentProviderModule {
    constructor() : this(null)

    override val id: String = ID
    override val name: String = "JIRA Enrichment"
    override val version: String = "1.0.0"
    override val dslName: String = "jira"
    override val configKey: String = "jira"
    override val defaultEnabled: Boolean = true
    override val defaultPriority: Int = 100
    override val description: String =
        "Extracts JIRA ticket keys and (when configured) fetches full ticket details via REST API."

    private var logger: Logger = NoOpLogger
    private val limiter = atomic<CachedEnrichment?>(null)
    private val jiraConfig = atomic<JiraConfig>(JiraConfig())
    private val apiClient = ReinitializableClient<JiraApiClient> { it.close() }
    private val skip = atomic<String?>(null)

    override val providedEnrichment: Set<String> =
        setOf(
            ENRICHMENT_TICKET_KEYS,
            EnrichmentKeys.JIRA_TICKETS,
            EnrichmentKeys.JIRA_ACCEPTANCE_CRITERIA,
            EnrichmentKeys.JIRA_LINKED_ISSUES,
        )

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        jiraConfig.value = decodeConfig(context.pluginConfig)
        limiter.value = CachedEnrichment(context, CACHE_NAME, jiraConfig.value.maxRequestsPerMinute, timeSource)
        initApiClient(jiraConfig.value, context)
        return true
    }

    private suspend fun initApiClient(
        cfg: JiraConfig,
        context: PluginContext,
    ) {
        skip.value = null
        val url = cfg.url.ifBlank { null }
        if (url == null) {
            skip.value = "url not set (KARANDASH_JIRA_URL): ticket keys only, no ticket details"
            return apiClient.close()
        }
        val token = cfg.token ?: context.env("JIRA_TOKEN")
        if (token == null) {
            skip.value = "url set but JIRA_TOKEN not set: ticket keys only, no ticket details"
            logger.warn(
                "Jira enrichment: url configured but JIRA_TOKEN env var not set — API enrichment disabled",
            )
            return apiClient.close()
        }
        val auth = EnrichmentHttp.atlassianAuth(cfg.email ?: context.env("JIRA_EMAIL"), token)
        apiClient.rebuild(listOf(url, auth, context.allowLocalhost)) {
            JiraApiClient(EnrichmentHttp.client(context, "Jira", url, auth, testEngine), url, logger)
        }
        logger.debug("Jira enrichment: API client initialized for $url")
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
        // no Jira url: only ticket keys are extracted, which is cheap, so skip the cache (no phantom miss in `cache stats`)
        if (jiraConfig.value.url.isBlank()) return doEnrich(mr)
        val cached = limiter.value ?: return doEnrich(mr)
        return cached.enrich(CachedEnrichment.mrTextDependencies(mr, "apiUrl" to jiraConfig.value.url)) { doEnrich(mr) }
    }

    /** Runs [block] inside the `max-requests-per-minute` window; issue and remote-link fetches share it. */
    private suspend fun <T> limited(block: suspend () -> T): T {
        val cached = limiter.value ?: return block()
        return cached.request(block)
    }

    private suspend fun doEnrich(mr: MergeRequest): Map<String, JsonElement> {
        val result = mutableMapOf<String, JsonElement>()
        if (jiraConfig.value.ticketKeys.enabled) appendTicketKeys(mr, result)
        val client = apiClient.value ?: return result
        val keys = extractTicketKeys(mr).take(jiraConfig.value.tickets.maxPerMr)
        if (keys.isEmpty()) return result
        val issues = fetchIssues(client, keys)
        if (jiraConfig.value.tickets.enabled && issues.isNotEmpty()) {
            result[EnrichmentKeys.JIRA_TICKETS] = encodeTickets(issues)
        }
        if (jiraConfig.value.acceptanceCriteria.enabled && issues.isNotEmpty()) {
            result[EnrichmentKeys.JIRA_ACCEPTANCE_CRITERIA] = encodeAc(issues)
        }
        if (jiraConfig.value.linkedIssues.enabled && issues.isNotEmpty()) {
            result[EnrichmentKeys.JIRA_LINKED_ISSUES] = encodeLinkedIssues(issues, client)
        }
        return result
    }

    private fun appendTicketKeys(
        mr: MergeRequest,
        result: MutableMap<String, JsonElement>
    ) {
        val keys = extractTicketKeys(mr)
        if (keys.isEmpty()) {
            logger.debug("No JIRA ticket keys found in MR metadata")
            return
        }
        logger.debug("Extracted JIRA ticket keys: ${keys.joinToString(", ")}")
        result[ENRICHMENT_TICKET_KEYS] = JsonArray(keys.map { JsonPrimitive(it) })
    }

    private suspend fun fetchIssues(
        client: JiraApiClient,
        keys: List<String>,
    ): List<JiraIssueResponse> {
        val results = mutableListOf<JiraIssueResponse>()
        val acField = jiraConfig.value.acceptanceCriteria.field
        for (key in keys) {
            val issue = limited { client.fetchIssue(key, acField) }
            if (issue != null) {
                results.add(issue)
            } else {
                logger.warn("Jira enrichment: failed to fetch or parse issue $key")
            }
        }
        logger.debug("Jira enrichment: fetched ${results.size}/${keys.size} issues")
        return results
    }

    private fun encodeTickets(issues: List<JiraIssueResponse>): JsonElement {
        val ticketMap = issues.associate { it.key to encodeTicketDetails(it) }
        return KarandashJson.Default.encodeToJsonElement(
            MapSerializer(String.serializer(), JsonElement.serializer()),
            ticketMap,
        )
    }

    private fun encodeTicketDetails(issue: JiraIssueResponse): JsonElement =
        KarandashJson.Default.encodeToJsonElement(
            JiraTicketDetails.serializer(),
            JiraTicketDetails(
                key = issue.key,
                summary = issue.fields.summary,
                description = AdfToMarkdown.convert(issue.fields.description),
                type = issue.fields.issueType?.name ?: "",
                priority = issue.fields.priority?.name ?: "",
                status = issue.fields.status?.name ?: "",
                labels = issue.fields.labels,
                assignee = issue.fields.assignee?.displayName,
                epicKey = issue.fields.epicKey,
            ),
        )

    private fun encodeAc(issues: List<JiraIssueResponse>): JsonElement {
        val acMap = issues.associate { issue ->
            val bullets = AcceptanceCriteriaExtractor.extract(
                descriptionAdf = issue.fields.description,
                customFieldAdf = issue.acceptanceField,
            )
            issue.key to KarandashJson.Default.encodeToJsonElement(
                ListSerializer(String.serializer()),
                bullets,
            )
        }
        return KarandashJson.Default.encodeToJsonElement(
            MapSerializer(String.serializer(), JsonElement.serializer()),
            acMap,
        )
    }

    private suspend fun encodeLinkedIssues(
        issues: List<JiraIssueResponse>,
        client: JiraApiClient,
    ): JsonElement {
        val remoteLinks = issues.associate { issue -> issue.key to limited { client.fetchRemoteLinks(issue.key) } }
        val linkedMap = issues.associate { issue ->
            val links = issue.fields.issueLinks.flatMap { link ->
                buildList {
                    link.inwardIssue?.let { ref ->
                        add(
                            JiraLinkedIssue(
                                relation = link.type.inward,
                                targetKey = ref.key,
                                summary = ref.fields?.summary ?: "",
                                status = ref.fields?.status?.name ?: "",
                                remoteUrl = null,
                            )
                        )
                    }
                    link.outwardIssue?.let { ref ->
                        add(
                            JiraLinkedIssue(
                                relation = link.type.outward,
                                targetKey = ref.key,
                                summary = ref.fields?.summary ?: "",
                                status = ref.fields?.status?.name ?: "",
                                remoteUrl = null,
                            )
                        )
                    }
                }
            } + remoteLinks[issue.key].orEmpty().mapNotNull { remote ->
                remote.url?.let {
                    JiraLinkedIssue(relation = REMOTE_LINK, targetKey = "", summary = remote.title ?: "", status = "", remoteUrl = it)
                }
            }
            issue.key to KarandashJson.Default.encodeToJsonElement(
                ListSerializer(JiraLinkedIssue.serializer()),
                links,
            )
        }
        return KarandashJson.Default.encodeToJsonElement(
            MapSerializer(String.serializer(), JsonElement.serializer()),
            linkedMap,
        )
    }

    private fun decodeConfig(config: JsonElement): JiraConfig =
        EnrichmentConfigParser.decodeOrDefault(config, JiraConfig.serializer(), KNOWN_KEYS, ID, logger::warn) { JiraConfig() }

    companion object {
        const val ID = "jira"

        /** Ticket cache name; `CacheCategory.JIRA` in core/cache claims it for `cache stats`. */
        const val CACHE_NAME = "enrichment:jira"
        private const val REMOTE_LINK = "remote link"
        const val ENRICHMENT_TICKET_KEYS = EnrichmentKeys.JIRA_TICKET_KEYS
        private val KNOWN_KEYS = setOf(
            "email",
            "ticket-keys",
            "tickets",
            "acceptance-criteria",
            "linked-issues",
            "max-requests-per-minute",
        )

        /**
         * Matches JIRA-style ticket keys: 2–10 uppercase letters, hyphen, 1+ digits.
         * Uses lookaround to avoid matching substrings embedded in longer tokens.
         * Known false-positive prefixes (tech abbreviations like SHA, UTF, ISO, KTOR)
         * are filtered out in [extractTicketKeys] via [FALSE_POSITIVE_PREFIXES].
         */
        internal val JIRA_KEY_REGEX = Regex("(?<![A-Z0-9])([A-Z]{2,10}-[1-9][0-9]{0,5})(?![0-9A-Z])")

        /**
         * Known uppercase prefixes that produce false-positive JIRA key matches.
         * These are tech abbreviations (hash algorithms, encodings, library names)
         * that happen to match the `LETTERS-DIGITS` pattern.
         */
        internal val FALSE_POSITIVE_PREFIXES = setOf(
            "SHA",
            "MD",
            "UTF",
            "ISO",
            "AES",
            "RSA",
            "DSA",
            "SSL",
            "TLS",
            "TCP",
            "UDP",
            "HTTP",
            "HTTPS",
            "SMTP",
            "IMAP",
            "FTP",
            "SSH",
            "KTOR",
            "JDK",
            "JRE",
            "JVM",
            "GCC",
            "LLVM",
            "MSVC",
            "CVE",
            "CWE",
            "RFC",
            "IEEE",
        )
    }
}

/**
 * Extracts unique JIRA ticket keys from the merge request title, description,
 * source branch, and commit messages.
 */
internal fun extractTicketKeys(mr: MergeRequest): List<String> {
    val sources = buildList {
        add(mr.title)
        mr.description?.let { add(it) }
        add(mr.sourceBranch)
        mr.commits.forEach { add(it.message) }
    }
    val falsePrefixes = JiraEnrichmentProvider.FALSE_POSITIVE_PREFIXES
    return sources
        .flatMap { JiraEnrichmentProvider.JIRA_KEY_REGEX.findAll(it).map { m -> m.groupValues[1] } }
        .filter { key -> key.substringBefore('-') !in falsePrefixes }
        .distinct()
}
