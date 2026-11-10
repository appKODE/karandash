package io.karandash.platforms.gitlab

import io.karandash.models.support.KarandashJson
import io.karandash.providers.common.CredentialsSupport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object GitLabCredentials {
    @Serializable
    data class Resolved(
        val token: String?,
        val baseUrl: String,
        val approveToken: String? = null,
        /** True when [token] came from `CI_JOB_TOKEN`, which GitLab accepts only in the `JOB-TOKEN` header. */
        val jobToken: Boolean = false,
    )

    /**
     * Resolve GitLab credentials from config and environment.
     *
     * Precedence:
     * - Token: config.token > GITLAB_TOKEN > CI_JOB_TOKEN (optional: anonymous reads of public projects;
     *   `checkAuth` reports a missing or bad token)
     * - URL: config.url > CI_SERVER_URL > GITLAB_URL > "https://gitlab.com"
     */
    fun resolve(
        config: JsonElement,
        env: (String) -> String?
    ): Resolved {
        val obj = config as? JsonObject ?: JsonObject(emptyMap())
        val cfg = KarandashJson.Default.decodeFromJsonElement(GitLabConfig.serializer(), obj)

        val explicitToken = CredentialsSupport.firstNonBlank(cfg.token, env, "GITLAB_TOKEN")
        val token = explicitToken ?: CredentialsSupport.firstNonBlank(null, env, "CI_JOB_TOKEN")

        val baseUrl = if (obj.containsKey("url") ||
            obj.containsKey("baseUrl")
        ) {
            cfg.url
        } else {
            env("CI_SERVER_URL") ?: env("GITLAB_URL") ?: cfg.url
        }

        val approveToken = CredentialsSupport.firstNonBlank(cfg.approveToken, env, "GITLAB_APPROVE_TOKEN")

        return Resolved(
            token = token,
            baseUrl = baseUrl.trimEnd().trimEnd('/'),
            approveToken = approveToken,
            jobToken = explicitToken == null && token != null,
        )
    }

    fun supportedEnvVars(): Set<String> =
        setOf(
            "GITLAB_TOKEN",
            "CI_JOB_TOKEN",
            "CI_SERVER_URL",
            "GITLAB_URL",
            "GITLAB_APPROVE_TOKEN",
        )
}
