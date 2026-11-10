@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.enrichment.gitlabissue

import io.karandash.api.PluginContext
import io.karandash.http.HttpAuth
import io.karandash.providers.common.AbstractIssueEnrichmentProvider
import io.karandash.providers.common.EnrichmentHttp
import io.karandash.providers.common.IssueSubConfig
import io.karandash.sdk.enrichment.EnrichmentConfigParser
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.ktor.client.engine.HttpClientEngine
import kotlinx.atomicfu.atomic
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class GitLabIssueEnrichmentProvider internal constructor(
    private val testEngine: HttpClientEngine? = null,
) : AbstractIssueEnrichmentProvider("GitLab issue enrichment", EnrichmentKeys.GITLAB_ISSUES) {
    constructor() : this(null)

    override val id: String = ID
    override val name: String = "GitLab Issue Enrichment"
    override val version: String = "1.0.0"
    override val dslName: String = CONFIG_KEY
    override val configKey: String = CONFIG_KEY
    override val defaultEnabled: Boolean = false
    override val defaultPriority: Int = 100
    override val description: String =
        "Fetches GitLab issue details referenced in the MR title, description, and commits."

    private val issueConfig = atomic(GitLabIssueConfig())

    override fun decode(config: JsonElement): IssueSubConfig? =
        EnrichmentConfigParser
            .decodeOrDefault(
                withStringProjectId(config),
                GitLabIssueConfig.serializer(),
                KNOWN_KEYS,
                ID,
                logger::warn,
            ) { null }
            ?.also { issueConfig.value = it }
            ?.issues

    override suspend fun connect(context: PluginContext): String? {
        val config = issueConfig.value
        // GITLAB_TOKEN (PRIVATE-TOKEN), then a GitLab CI job's CI_JOB_TOKEN (JOB-TOKEN)
        val privateToken = config.token ?: context.env("GITLAB_TOKEN")
        val jobToken = context.env("CI_JOB_TOKEN")
        val auth = when {
            privateToken != null -> HttpAuth.CustomHeader(PRIVATE_TOKEN_HEADER, privateToken)
            jobToken != null -> HttpAuth.CustomHeader(JOB_TOKEN_HEADER, jobToken)
            else -> return "no GITLAB_TOKEN or CI_JOB_TOKEN: issue references not fetched"
        }
        val projectId = config.projectId.ifBlank { context.env("CI_PROJECT_ID").orEmpty() }
        if (projectId.isBlank()) return "no project-id and no CI_PROJECT_ID: issue references not fetched"
        val ciServer = context.env("CI_SERVER_URL")?.takeUnless { it.isBlank() }
        val url = if (config.url == GitLabIssueConfig.DEFAULT_BASE_URL && ciServer != null) ciServer else config.url

        connectClient(listOf(url, projectId, auth, context.allowLocalhost)) {
            GitLabIssueClient(EnrichmentHttp.client(context, "GitLabIssue", url, auth, testEngine), url, projectId, logger)
        }
        logger.debug("GitLab issue enrichment: initialized for project $projectId")
        return null
    }

    override fun extract(texts: List<String>): Collection<Int> = GitLabIssueExtractor.extract(texts)

    /** `project-id` may be written as a number or a string (numeric id or `group/repo` path). */
    private fun withStringProjectId(config: JsonElement): JsonElement {
        if (config !is JsonObject) return config
        return JsonObject(
            config.mapValues { (key, value) ->
                if ((key == "project-id" || key == "projectId") && value is JsonPrimitive &&
                    value !is JsonNull
                ) {
                    JsonPrimitive(value.content)
                } else {
                    value
                }
            },
        )
    }

    companion object {
        const val ID = "gitlab-issue"
        const val CONFIG_KEY = ID
        private const val PRIVATE_TOKEN_HEADER = "PRIVATE-TOKEN"
        private const val JOB_TOKEN_HEADER = "JOB-TOKEN"
        private val KNOWN_KEYS = setOf("project-id", "issues")
    }
}
