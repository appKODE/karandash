@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.enrichment.github

import io.karandash.api.PluginContext
import io.karandash.http.HttpAuth
import io.karandash.models.MergeRequest
import io.karandash.providers.common.AbstractIssueEnrichmentProvider
import io.karandash.providers.common.CachedEnrichment
import io.karandash.providers.common.EnrichmentHttp
import io.karandash.providers.common.IssueSubConfig
import io.karandash.sdk.enrichment.EnrichmentConfigParser
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.ktor.client.engine.HttpClientEngine
import kotlinx.atomicfu.atomic
import kotlinx.serialization.json.JsonElement

class GitHubIssueEnrichmentProvider internal constructor(
    private val testEngine: HttpClientEngine? = null,
) : AbstractIssueEnrichmentProvider("GitHub issue enrichment", EnrichmentKeys.GITHUB_ISSUES) {
    constructor() : this(null)

    override val id: String = ID
    override val name: String = "GitHub Issue Enrichment"
    override val version: String = "1.0.0"
    override val dslName: String = CONFIG_KEY
    override val configKey: String = CONFIG_KEY
    override val defaultEnabled: Boolean = false
    override val defaultPriority: Int = 100
    override val description: String =
        "Fetches GitHub issue details referenced in the MR title, description, and commits."

    /** `owner/repo` the client was built for; short `#N` references resolve against it. */
    private val repoSlug = atomic("")

    private val issueConfig = atomic(GitHubIssueConfig())

    override fun decode(config: JsonElement): IssueSubConfig? =
        EnrichmentConfigParser
            .decodeOrDefault(config, GitHubIssueConfig.serializer(), KNOWN_KEYS, ID, logger::warn) { null }
            ?.also { issueConfig.value = it }
            ?.issues

    override suspend fun connect(context: PluginContext): String? {
        val config = issueConfig.value
        val token = config.token ?: context.env("GITHUB_TOKEN")
            ?: return "no GITHUB_TOKEN: issue references not fetched"
        // a GitHub Actions run names its repository in GITHUB_REPOSITORY (`owner/repo`)
        val envRepo = context.env("GITHUB_REPOSITORY").orEmpty().split('/')
        val owner = config.owner.ifBlank { envRepo.getOrNull(0).orEmpty() }
        val repo = config.repo.ifBlank { envRepo.getOrNull(1).orEmpty() }
        if (owner.isBlank() || repo.isBlank()) {
            return "no owner/repo and no GITHUB_REPOSITORY: issue references not fetched"
        }
        val envApi = context.env("GITHUB_API_URL")?.takeUnless { it.isBlank() }
        val url = if (config.url == GitHubIssueConfig.DEFAULT_BASE_URL && envApi != null) envApi else config.url

        repoSlug.value = "$owner/$repo"
        val auth = HttpAuth.Bearer(token)
        connectClient(listOf(url, owner, repo, auth, context.allowLocalhost)) {
            GitHubIssueClient(EnrichmentHttp.client(context, "GitHubIssue", url, auth, testEngine), url, owner, repo, logger)
        }
        logger.debug("GitHub issue enrichment: initialized for $owner/$repo")
        return null
    }

    override fun extract(texts: List<String>): Collection<Int> = GitHubIssueExtractor.extract(texts, repoSlug.value)

    override fun cacheDependencies(mr: MergeRequest): Map<String, String> =
        CachedEnrichment.mrTextDependencies(mr, "repo" to repoSlug.value)

    companion object {
        const val ID = "github-issue"
        const val CONFIG_KEY = ID
        private val KNOWN_KEYS = setOf("owner", "repo", "issues")
    }
}
