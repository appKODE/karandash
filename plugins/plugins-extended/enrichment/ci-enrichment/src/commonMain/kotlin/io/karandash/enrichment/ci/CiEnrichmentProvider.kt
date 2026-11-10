@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.enrichment.ci

import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.http.HttpAuth
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import io.karandash.providers.common.EnrichmentHttp
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.enrichment.EnrichmentConfigParser
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.sdk.platform.ReinitializableClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.atomicfu.atomic
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class CiEnrichmentProvider internal constructor(
    private val testEngine: HttpClientEngine? = null,
) : EnrichmentProviderModule {
    constructor() : this(null)

    override val id: String = ID
    override val name: String = "CI Enrichment"
    override val version: String = "1.0.0"
    override val dslName: String = CONFIG_KEY
    override val configKey: String = CONFIG_KEY
    override val defaultEnabled: Boolean = false
    override val defaultPriority: Int = 100
    override val description: String = "Extracts CI job failures and coverage gaps for AI context."

    private var logger: Logger = NoOpLogger
    private val ciConfig = atomic<CiEnrichmentConfig>(CiEnrichmentConfig())
    private val workingDirectory = atomic<String>("")
    private val jobsApi = ReinitializableClient<CiJobsApi> { it.close() }
    private val skip = atomic<String?>(null)

    override val providedEnrichment: Set<String> get() = setOf(EnrichmentKeys.CI_FAILED_JOBS, EnrichmentKeys.CI_COVERAGE_GAPS)

    override fun requiredEnrichment(): List<String> = emptyList()

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        workingDirectory.value = context.workingDirectory
        ciConfig.value = decodeConfig(context.pluginConfig)
        val cfg = ciConfig.value
        val platform = cfg.platform?.takeUnless { it.isBlank() }?.lowercase()
            ?: if (context.env("GITHUB_ACTIONS") == "true") PLATFORM_GITHUB else PLATFORM_GITLAB
        val target = when (platform) {
            PLATFORM_GITLAB -> gitLabTarget(cfg, context)
            PLATFORM_GITHUB -> gitHubTarget(cfg, context)
            else -> null
        }
        skip.value = null
        val auth = target?.auth
        if (target == null || target.url.isBlank() || target.project.isBlank() || auth == null) {
            jobsApi.close()
            skip.value = noApiReason(platform, cfg, target)
            return true
        }
        jobsApi.rebuild(listOf(platform, target.url, target.project, auth, context.allowLocalhost)) {
            val http = EnrichmentHttp.client(context, "CI", target.url, auth, testEngine)
            if (platform == PLATFORM_GITHUB) {
                GitHubActionsClient(http, target.url, target.project, logger)
            } else {
                CiPipelineClient(http, target.url, target.project, logger)
            }
        }
        logger.debug("CI enrichment: initialized with $platform API at '${target.url}'")
        return true
    }

    /** Why no pipeline API is used, logged once; null when the API is simply not configured. */
    private fun noApiReason(
        platform: String,
        cfg: CiEnrichmentConfig,
        target: ApiTarget?,
    ): String? =
        when {
            target == null && cfg.url.isNotBlank() && cfg.projectId.isNotBlank() -> {
                logger.warn(
                    "CI enrichment: platform '$platform' has no pipeline API support (only '$PLATFORM_GITLAB' and " +
                        "'$PLATFORM_GITHUB') — using mr.ciJobs only (no log snippets)",
                )
                "platform '$platform' has no pipeline API: jobs from the MR only, no log snippets"
            }

            target != null && target.url.isNotBlank() && target.project.isNotBlank() -> {
                "API configured but no ${target.tokenVars}: jobs from the MR only, no log snippets"
            }

            else -> {
                logger.info("CI enrichment: no API config — using mr.ciJobs only (no log snippets)")
                null
            }
        }

    /** GitLab: config, then the GitLab CI job's `CI_SERVER_URL` / `CI_PROJECT_ID`; `GITLAB_TOKEN`, then `CI_JOB_TOKEN`. */
    private fun gitLabTarget(
        cfg: CiEnrichmentConfig,
        context: PluginContext,
    ): ApiTarget {
        // Only GitLab tokens: the client talks to the GitLab API, so never fall back to GITHUB_TOKEN.
        val privateToken = cfg.token?.takeUnless { it.isBlank() } ?: context.env("GITLAB_TOKEN")?.takeUnless { it.isBlank() }
        val jobToken = context.env("CI_JOB_TOKEN")?.takeUnless { it.isBlank() }
        val auth = when {
            privateToken != null -> HttpAuth.CustomHeader(PRIVATE_TOKEN_HEADER, privateToken)
            jobToken != null -> HttpAuth.CustomHeader(JOB_TOKEN_HEADER, jobToken)
            else -> null
        }
        return ApiTarget(
            url = cfg.url.ifBlank { context.env("CI_SERVER_URL").orEmpty() },
            project = cfg.projectId.ifBlank { context.env("CI_PROJECT_ID").orEmpty() },
            auth = auth,
            tokenVars = "GITLAB_TOKEN or CI_JOB_TOKEN",
        )
    }

    /** GitHub Actions: config, then the workflow's `GITHUB_API_URL` / `GITHUB_REPOSITORY`; `GITHUB_TOKEN`. */
    private fun gitHubTarget(
        cfg: CiEnrichmentConfig,
        context: PluginContext,
    ): ApiTarget {
        val token = cfg.token?.takeUnless { it.isBlank() } ?: context.env("GITHUB_TOKEN")?.takeUnless { it.isBlank() }
        return ApiTarget(
            url = cfg.url.ifBlank { context.env("GITHUB_API_URL")?.takeUnless { it.isBlank() } ?: GITHUB_API_DEFAULT },
            project = cfg.projectId.ifBlank { context.env("GITHUB_REPOSITORY").orEmpty() },
            auth = token?.let { HttpAuth.Bearer(it) },
            tokenVars = "GITHUB_TOKEN",
        )
    }

    private class ApiTarget(
        val url: String,
        val project: String,
        val auth: HttpAuth?,
        val tokenVars: String,
    )

    override fun skipReason(): String? = skip.value

    override suspend fun shutdown() {
        jobsApi.close()
        workingDirectory.value = ""
    }

    override suspend fun enrich(
        mr: MergeRequest,
        config: JsonElement,
        upstreamContext: EnrichmentContext,
    ): Map<String, JsonElement> {
        val result = mutableMapOf<String, JsonElement>()
        if (ciConfig.value.failedJobs.enabled) {
            val jobs = buildFailedJobs(mr)
            if (jobs.isNotEmpty()) {
                result[EnrichmentKeys.CI_FAILED_JOBS] = KarandashJson.Default.encodeToJsonElement(
                    ListSerializer(FailedJob.serializer()),
                    jobs,
                )
                logger.debug("CI enrichment: emitted ${jobs.size} failed job(s)")
            }
        }
        if (ciConfig.value.coverageGaps.enabled) {
            val changedPaths = mr.changes.map { it.path }.filter { it.isNotBlank() }
            val patterns = ciConfig.value.coverageGaps.artifactPatterns
            val gaps = CiCoverageScanner.scan(workingDirectory.value, changedPaths, patterns, logger)
            if (gaps.isNotEmpty()) {
                val serialized = gaps.mapValues { (_, ranges) ->
                    KarandashJson.Default.encodeToJsonElement(ListSerializer(UncoveredRange.serializer()), ranges)
                }
                result[EnrichmentKeys.CI_COVERAGE_GAPS] = JsonObject(serialized)
                logger.debug("CI enrichment: emitted coverage gaps for ${gaps.size} file(s)")
            }
        }
        return result
    }

    private suspend fun buildFailedJobs(mr: MergeRequest): List<FailedJob> {
        val basicFailed = mr.ciJobs.filter { it.status == STATUS_FAILED }
        if (basicFailed.isEmpty()) return emptyList()
        val maxJobs = ciConfig.value.failedJobs.maxJobs
        val apiJobs = jobsApi.value?.failedJobs(mr, maxJobs).orEmpty()
        return apiJobs.ifEmpty { basicFailed.take(maxJobs).map { FailedJob(name = it.name) } }
    }

    private fun decodeConfig(config: JsonElement): CiEnrichmentConfig =
        EnrichmentConfigParser.decodeOrDefault(
            config,
            CiEnrichmentConfig.serializer(),
            KNOWN_KEYS,
            ID,
            logger::warn
        ) { CiEnrichmentConfig() }

    companion object {
        const val ID = "ci"
        const val CONFIG_KEY = ID
        private const val STATUS_FAILED = "failed"
        private const val PLATFORM_GITLAB = "gitlab"
        private const val PLATFORM_GITHUB = "github"
        private const val GITHUB_API_DEFAULT = "https://api.github.com"
        private val KNOWN_KEYS = setOf("platform", "project-id", "failed-jobs", "coverage-gaps")
    }
}
