package io.karandash.platforms.gitea

import io.karandash.api.platform.CIEnvironment
import io.karandash.models.support.KarandashJson
import io.karandash.providers.common.CredentialsSupport
import io.karandash.providers.common.ForgeCiEnvironment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object GiteaCredentials {
    private const val GITEA_URL = "GITEA_URL"
    private const val GITEA_SERVER_URL = "GITEA_SERVER_URL"
    private const val GITEA_TOKEN = "GITEA_TOKEN"
    private const val GITHUB_SERVER_URL = "GITHUB_SERVER_URL"
    private const val GITHUB_TOKEN = "GITHUB_TOKEN"

    @Serializable
    data class Resolved(
        val baseUrl: String,
        val token: String?
    )

    /**
     * Resolve Gitea credentials from config and environment.
     *
     * Precedence:
     * - URL: config.url (alias baseUrl) > GITEA_URL > GITEA_SERVER_URL > GITHUB_SERVER_URL under Actions (optional, null if none)
     * - Token: config.token > GITEA_TOKEN (optional)
     */
    fun resolve(
        config: JsonElement,
        env: (String) -> String?
    ): Resolved? {
        val cfg = CredentialsSupport.decodeOrDefault(config, GiteaConfig.serializer()) { GiteaConfig() }

        val actions = isActions(env)
        val baseUrl = cfg.url
            ?: env(GITEA_URL)
            ?: env(GITEA_SERVER_URL)
            ?: env(GITHUB_SERVER_URL)?.takeIf { actions }
            ?: return null

        val token = CredentialsSupport.firstNonBlank(cfg.token, env, GITEA_TOKEN)
            ?: env(GITHUB_TOKEN)?.takeIf { actions && it.isNotBlank() }

        return Resolved(
            baseUrl = baseUrl.trimEnd('/'),
            token = token
        )
    }

    fun supportedEnvVars(): Set<String> =
        setOf(
            GITEA_URL,
            GITEA_SERVER_URL,
            GITEA_TOKEN,
            GITHUB_SERVER_URL,
            GITHUB_TOKEN,
        )

    /** Gitea and Forgejo Actions, which name the job's server, repository, ref and token with `GITHUB_*` variables. */
    fun isActions(env: (String) -> String?): Boolean = env("GITEA_ACTIONS") == "true" || env("FORGEJO_ACTIONS") == "true"

    /**
     * The CI build this runs in: Gitea/Forgejo Actions, Woodpecker on a Gitea or Forgejo forge, or a job exporting
     * `GITEA_SERVER_URL` (or `GITEA_URL`) with `GITEA_REPO_OWNER`, `GITEA_REPO_NAME` and `GITEA_PR_ID`.
     */
    fun detectCi(
        platform: String,
        env: (String) -> String?,
    ): CIEnvironment? {
        if (isActions(env)) {
            return CIEnvironment(
                platform = platform,
                projectId = env("GITHUB_REPOSITORY"),
                mrId = CIEnvironment.pullRequestNumber(env("GITHUB_REF")),
                token = env(GITEA_TOKEN) ?: env(GITHUB_TOKEN),
                baseUrl = env(GITHUB_SERVER_URL),
            )
        }
        ForgeCiEnvironment.woodpecker(env, platform, setOf("gitea", "forgejo"))?.let { return it }
        val giteaUrl = env(GITEA_SERVER_URL) ?: env(GITEA_URL) ?: return null
        val projectId = env("GITEA_REPO_OWNER")?.let { owner -> env("GITEA_REPO_NAME")?.let { repo -> "$owner/$repo" } }
        return CIEnvironment(platform, projectId, env("GITEA_PR_ID"), env(GITEA_TOKEN), giteaUrl)
    }
}
