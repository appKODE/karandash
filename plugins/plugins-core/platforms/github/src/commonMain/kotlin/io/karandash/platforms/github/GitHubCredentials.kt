package io.karandash.platforms.github

import io.karandash.models.support.KarandashJson
import io.karandash.providers.common.CredentialsSupport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object GitHubCredentials {
    /** [token] for token auth, [app] for GitHub App auth: exactly one is set. */
    @Serializable
    data class Resolved(
        val token: String?,
        val baseUrl: String,
        val app: GitHubAppCredentials? = null,
    )

    private val DIGITS = Regex("[0-9]+")

    /**
     * Resolve GitHub credentials from config and environment.
     *
     * Precedence:
     * - `auth.type: app`: `auth.appId`, `auth.installationId` and [appPrivateKeyPem] (the resolved `auth.privateKey`)
     * - Token: config.token > GITHUB_TOKEN (optional: anonymous reads of public repositories; `checkAuth`
     *   reports a missing or bad token)
     * - URL: config.url/baseUrl > GITHUB_API_URL > "https://api.github.com"
     */
    fun resolve(
        config: JsonElement,
        env: (String) -> String?,
        appPrivateKeyPem: String? = null,
    ): Resolved {
        val obj = config as? JsonObject ?: JsonObject(emptyMap())
        val cfg = KarandashJson.Default.decodeFromJsonElement(GitHubConfig.serializer(), obj)

        val app = cfg.auth?.let { resolveApp(it, appPrivateKeyPem) }
        val token = if (app != null) {
            null
        } else {
            CredentialsSupport.firstNonBlank(cfg.token, env, "GITHUB_TOKEN")
        }

        val baseUrl = when {
            obj.containsKey("url") -> cfg.url
            cfg.baseUrl != null -> cfg.baseUrl
            else -> env("GITHUB_API_URL") ?: cfg.url
        }

        return Resolved(
            token = token,
            baseUrl = baseUrl.trimEnd('/'),
            app = app,
        )
    }

    private fun resolveApp(
        auth: GitHubAuthConfig,
        privateKeyPem: String?,
    ): GitHubAppCredentials? {
        when (auth.type.lowercase()) {
            GitHubAuthConfig.TYPE_TOKEN -> return null
            GitHubAuthConfig.TYPE_APP -> Unit
            else -> error("GitHub auth.type '${auth.type}' is not supported; use 'token' or 'app'")
        }
        val appId = auth.appId?.takeIf { it.isNotBlank() } ?: error("GitHub App auth needs auth.appId")
        val installationId = auth.installationId?.takeIf { DIGITS.matches(it) }
            ?: error("GitHub App auth needs a numeric auth.installationId")
        val pem = privateKeyPem?.takeIf { it.isNotBlank() } ?: error("GitHub App auth needs auth.privateKey (PEM)")
        return GitHubAppCredentials(appId.trim(), installationId, pem)
    }

    fun supportedEnvVars(): Set<String> =
        setOf(
            "GITHUB_TOKEN",
            "GITHUB_API_URL"
        )
}
