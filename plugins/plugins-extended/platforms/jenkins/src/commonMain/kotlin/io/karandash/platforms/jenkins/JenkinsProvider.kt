package io.karandash.platforms.jenkins

import io.karandash.api.ModuleRegistry
import io.karandash.api.PlatformServiceConfig
import io.karandash.api.PluginContext
import io.karandash.api.finding.MessageCode
import io.karandash.api.finding.ModuleMessage
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.CiStatusSource
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Typed configuration for [JenkinsProvider].
 *
 * @param changeUrl Fallback merge request URL used when the `CHANGE_URL` environment variable is unset
 *   (e.g. pipeline jobs that are not multibranch PR builds).
 * @param url Jenkins root for the build JSON API; overrides `JENKINS_URL` (alias `baseUrl`).
 * @param user API user; overrides `JENKINS_USER`.
 * @param token API token; overrides `JENKINS_API_TOKEN` / `JENKINS_TOKEN`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JenkinsConfig(
    val changeUrl: String? = null,
    @JsonNames("baseUrl") val url: String? = null,
    val user: String? = null,
    val token: String? = null,
) : PlatformServiceConfig {
    companion object
}

/**
 * Jenkins CI provider module.
 *
 * Jenkins is a CI-only provider: it detects the Jenkins CI environment and parses
 * the `CHANGE_URL` environment variable to determine which underlying platform
 * (GitLab, GitHub, Bitbucket, Gerrit, Azure DevOps, or Gitea) should handle the
 * actual merge request fetching.
 *
 * It also reads the current build's result from the Jenkins JSON API ([fetchCiStatus]).
 *
 * This provider does not fetch merge requests directly. The CLI uses the
 * [CIEnvironment.platform] value returned by [detectCiEnvironment] to locate
 * the appropriate platform provider for data fetching.
 */
class JenkinsProvider internal constructor(
    private val registry: ModuleRegistry,
    private val engine: HttpClientEngine?,
) : AbstractTypedPlatformServiceModule<JenkinsConfig>(JenkinsConfig.serializer()),
    CiStatusSource {
    constructor(registry: ModuleRegistry) : this(registry, null)

    override val id: String = ID
    override val name: String = "Jenkins CI"
    override val version: String = "1.7.0"
    override val defaultPriority: Int = 80

    override val hoverDocumentation: String = HOVER_DOCUMENTATION

    override val description: String =
        "Jenkins CI provider. Detects Jenkins environment and delegates MR fetching to the underlying platform provider."

    private val config = MutableStateFlow(JenkinsConfig())
    private val allowLocalhost = MutableStateFlow(false)
    private var logger: Logger = NoOpLogger

    override suspend fun initializeTyped(
        context: PluginContext,
        config: JenkinsConfig
    ): Boolean {
        this.config.value = config
        allowLocalhost.value = context.allowLocalhost
        logger = context.logger
        context.log("Jenkins CI provider module initialized")
        return true
    }

    /**
     * Detect Jenkins CI environment.
     *
     * Requires `JENKINS_URL` and either `CHANGE_URL` or [JenkinsConfig.changeUrl]. The change URL is parsed
     * to determine the underlying platform (GitLab, GitHub, or Bitbucket) and extract
     * the project ID and MR/PR ID.
     *
     * @return [CIEnvironment] with the detected underlying platform, or null if not in Jenkins CI
     */
    override suspend fun detectCiEnvironment(env: (String) -> String?): CIEnvironment? {
        env("JENKINS_URL") ?: return null
        val changeUrl = env("CHANGE_URL") ?: config.value.changeUrl ?: return null

        val parsed = parseMergeRequestUrl(changeUrl) ?: return null

        return CIEnvironment(
            platform = parsed.platform,
            projectId = parsed.projectId,
            mrId = parsed.mrId,
            token = null,
            baseUrl = parsed.baseUrl
        )
    }

    /**
     * CI status of the current Jenkins build, read from the build's JSON API (`BUILD_URL` + `/api/json`).
     *
     * Authenticates with `user`/`token` from config, else `JENKINS_USER` and `JENKINS_API_TOKEN`, when set. The
     * Jenkins root is config `url`, else `JENKINS_URL`. Returns `success`, `failed` or `pending`,
     * or null outside Jenkins or when the build cannot be read. `JENKINS_URL` passes the same base-URL check as other
     * platforms (`allow_localhost` honoured), and `BUILD_URL` must be on its host. The host reads it through
     * [CiStatusSource] when the merge request's own platform reported no CI status.
     */
    override suspend fun fetchCiStatus(env: (String) -> String?): String? {
        val cfg = config.value
        val jenkinsUrl = cfg.url ?: env("JENKINS_URL") ?: return null
        val buildUrl = env("BUILD_URL") ?: return null
        val fromEnv = JenkinsCredentials.resolve(env)
        val credentials = JenkinsCredentials(token = cfg.token ?: fromEnv.token, user = cfg.user ?: fromEnv.user)
        val client = try {
            JenkinsClient(jenkinsUrl.trimEnd('/'), credentials, logger, engine, allowLocalhost.value)
        } catch (e: IllegalArgumentException) {
            logger.warn("Jenkins: JENKINS_URL refused, build status not read: ${e.message}")
            return null
        }
        return try {
            client.fetchCiStatus(buildUrl)
        } finally {
            client.close()
        }
    }

    /**
     * Parse a Jenkins `CHANGE_URL` into structured components.
     *
     * Delegates to platform-specific URL parsing based on URL patterns:
     * - GitLab: contains `/-/merge_requests/`
     * - GitHub: contains `/pull/`
     * - Bitbucket: contains `/pull-requests/`
     *
     * @return [ParsedMergeRequestUrl] if the URL matches a known platform, null otherwise
     */
    override suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? {
        val providers = registry.platformServices()
        for (provider in providers) {
            if (provider.id == ID) continue
            val parsed = provider.parseMergeRequestUrl(url)
            if (parsed != null) return parsed
        }
        return parseChangeUrl(url)
    }

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String
    ): MergeRequest = throw UnsupportedPlatformOperationException("fetchMergeRequest")

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest =
        throw UnsupportedPlatformOperationException("fetchMergeRequestByUrl")

    override suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String,
    ): String = throw UnsupportedPlatformOperationException("postComment")

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String,
    ): Unit = throw UnsupportedPlatformOperationException("updateComment")

    override suspend fun findExistingComment(
        projectId: String,
        mrId: String,
        marker: String,
    ): String? = throw UnsupportedPlatformOperationException("findExistingComment")

    override suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int?,
    ): String = throw UnsupportedPlatformOperationException("postInlineComment")

    override suspend fun messages(): List<ModuleMessage> =
        listOf(
            ModuleMessage(
                MessageCode.MISSING_AUTH,
                "Jenkins CI environment not detected. Ensure JENKINS_URL and CHANGE_URL are set."
            )
        )

    companion object {
        const val ID = "jenkins"

        // Fallback platform IDs for parseChangeUrl when registry is unavailable.
        private const val PLATFORM_GITLAB = "gitlab"
        private const val PLATFORM_GITHUB = "github"
        private const val PLATFORM_BITBUCKET = "bitbucket"
        private const val PLATFORM_GERRIT = "gerrit"
        private const val PLATFORM_AZURE_DEVOPS = "azure-devops"
        private const val PLATFORM_GITEA = "gitea"
        private const val PLATFORM_AWS_CODECOMMIT = "awscodecommit"

        private val GITLAB_URL_REGEX =
            Regex("""https?://([^/]+)/(.+?)/-/merge_requests/(\d+)""")
        private val GITHUB_URL_REGEX =
            Regex("""https?://([^/]+)/([^/]+)/([^/]+)/pull/(\d+)""")
        private val BITBUCKET_URL_REGEX =
            Regex("""https?://([^/]+)/([^/]+)/([^/]+)/pull-requests/(\d+)""")
        private val GERRIT_URL_REGEX =
            Regex("""https?://([^/]+)/c/([^/]+)/\+/(\d+)""")
        private val AZURE_DEVOPS_URL_REGEX =
            Regex("""https?://([^/]+)/([^/]+)/([^/]+)/_git/([^/]+)/pullrequest/(\d+)""")
        private val GITEA_URL_REGEX =
            Regex("""https?://([^/]+)/([^/]+)/([^/]+)/pulls/(\d+)""")

        private fun baseUrlFromHost(host: String) = "https://$host"

        private val AWS_CODECOMMIT_URL_REGEX =
            Regex("""https?://(?:[^.]+\.)?console\.aws\.amazon\.com/codesuite/codecommit/repositories/([^/]+)/pull-requests/(\d+)""")

        /**
         * Parse a CHANGE_URL to determine the underlying platform and extract project/MR info.
         *
         * Supported URL formats:
         * - GitLab: `https://gitlab.com/group/project/-/merge_requests/123`
         * - GitHub: `https://github.com/org/repo/pull/456`
         * - Bitbucket: `https://bitbucket.org/team/repo/pull-requests/789`
         * - Gerrit: `https://gerrit.example.com/c/project-name/+/123`
         * - Azure DevOps: `https://dev.azure.com/org/project/_git/repo/pullrequest/123`
         * - Gitea: `https://gitea.example.com/owner/repo/pulls/123`
         * - AWS CodeCommit: `https://console.aws.amazon.com/codesuite/codecommit/repositories/{repo}/pull-requests/{id}/...`
         * - AWS CodeCommit (regional): `https://{region}.console.aws.amazon.com/codesuite/codecommit/repositories/{repo}/pull-requests/{id}/...`
         */
        internal fun parseChangeUrl(url: String): ParsedMergeRequestUrl? {
            GITLAB_URL_REGEX.find(url)?.let { match ->
                val host = match.groupValues[1]
                val projectPath = match.groupValues[2]
                val mrId = match.groupValues[3]
                return ParsedMergeRequestUrl(
                    platform = PLATFORM_GITLAB,
                    baseUrl = baseUrlFromHost(host),
                    projectId = projectPath,
                    mrId = mrId
                )
            }

            GITHUB_URL_REGEX.find(url)?.let { match ->
                val host = match.groupValues[1]
                val owner = match.groupValues[2]
                val repo = match.groupValues[3]
                val prId = match.groupValues[4]
                val apiBase = if (host == "github.com") {
                    "https://api.github.com"
                } else {
                    "https://$host/api/v3"
                }
                return ParsedMergeRequestUrl(
                    platform = PLATFORM_GITHUB,
                    baseUrl = apiBase,
                    projectId = "$owner/$repo",
                    mrId = prId
                )
            }

            BITBUCKET_URL_REGEX.find(url)?.let { match ->
                val workspace = match.groupValues[2]
                val repoSlug = match.groupValues[3]
                val prId = match.groupValues[4]
                return ParsedMergeRequestUrl(
                    platform = PLATFORM_BITBUCKET,
                    baseUrl = "https://api.bitbucket.org/2.0",
                    projectId = "$workspace/$repoSlug",
                    mrId = prId
                )
            }

            GERRIT_URL_REGEX.find(url)?.let { match ->
                val host = match.groupValues[1]
                val projectName = match.groupValues[2]
                val changeId = match.groupValues[3]
                return ParsedMergeRequestUrl(
                    platform = PLATFORM_GERRIT,
                    baseUrl = baseUrlFromHost(host),
                    projectId = projectName,
                    mrId = changeId
                )
            }

            AZURE_DEVOPS_URL_REGEX.find(url)?.let { match ->
                val host = match.groupValues[1]
                val org = match.groupValues[2]
                val project = match.groupValues[3]
                val repo = match.groupValues[4]
                val prId = match.groupValues[5]
                return ParsedMergeRequestUrl(
                    platform = PLATFORM_AZURE_DEVOPS,
                    baseUrl = baseUrlFromHost(host),
                    projectId = "$org/$project/$repo",
                    mrId = prId
                )
            }

            GITEA_URL_REGEX.find(url)?.let { match ->
                val host = match.groupValues[1]
                val owner = match.groupValues[2]
                val repo = match.groupValues[3]
                val prId = match.groupValues[4]
                return ParsedMergeRequestUrl(
                    platform = PLATFORM_GITEA,
                    baseUrl = baseUrlFromHost(host),
                    projectId = "$owner/$repo",
                    mrId = prId
                )
            }

            AWS_CODECOMMIT_URL_REGEX.find(url)?.let { match ->
                val repo = match.groupValues[1]
                val prId = match.groupValues[2]
                return ParsedMergeRequestUrl(
                    platform = PLATFORM_AWS_CODECOMMIT,
                    baseUrl = "https://codecommit.amazonaws.com",
                    projectId = repo,
                    mrId = prId
                )
            }

            return null
        }
    }
}

/** LSP hover text for this module's config block. */
private val HOVER_DOCUMENTATION = """
    # jenkins

    **Jenkins Provider**

    Detects merge requests from Jenkins CI environment variables.

    ## Properties
    - `url: String` - Jenkins instance URL
    - `token: String` - API token
""".trimIndent()
