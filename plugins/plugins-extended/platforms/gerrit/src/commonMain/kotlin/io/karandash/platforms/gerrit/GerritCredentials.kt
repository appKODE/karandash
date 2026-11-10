package io.karandash.platforms.gerrit

import io.karandash.api.platform.CIEnvironment
import io.karandash.models.support.KarandashJson
import io.karandash.providers.common.CredentialsSupport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object GerritCredentials {
    private const val GERRIT_URL = "GERRIT_URL"
    private const val GERRIT_CHANGE_URL = "GERRIT_CHANGE_URL"
    private const val GERRIT_HOST = "GERRIT_HOST"
    private const val GERRIT_SCHEME = "GERRIT_SCHEME"
    private const val GERRIT_USERNAME = "GERRIT_USERNAME"
    private const val GERRIT_TOKEN = "GERRIT_TOKEN"

    @Serializable
    data class Resolved(
        val baseUrl: String,
        val username: String?,
        val token: String?
    )

    /**
     * Resolve Gerrit credentials from config and environment.
     *
     * Precedence:
     * - URL: config.url (alias baseUrl) > GERRIT_URL > GERRIT_CHANGE_URL origin > GERRIT_HOST (optional, null if none)
     * - Username: config.username > GERRIT_USERNAME (optional)
     * - Token: config.token > GERRIT_TOKEN (optional)
     */
    fun resolve(
        config: JsonElement,
        env: (String) -> String?
    ): Resolved? {
        val cfg = CredentialsSupport.decodeOrDefault(config, GerritConfig.serializer()) { GerritConfig() }

        val baseUrl = cfg.url
            ?: ciBaseUrl(env)
            ?: return null

        val token = CredentialsSupport.firstNonBlank(cfg.token, env, GERRIT_TOKEN)
        val username = CredentialsSupport.firstNonBlank(cfg.username, env, GERRIT_USERNAME)

        return Resolved(
            baseUrl = baseUrl.trimEnd('/'),
            username = username,
            token = token
        )
    }

    fun supportedEnvVars(): Set<String> = setOf(GERRIT_URL, GERRIT_CHANGE_URL, GERRIT_HOST, GERRIT_SCHEME, GERRIT_USERNAME, GERRIT_TOKEN)

    /**
     * The Gerrit web root: `GERRIT_URL`, else the origin of the Gerrit Trigger's `GERRIT_CHANGE_URL`
     * (`https://host[/prefix]/c/<project>/+/<n>` or `https://host[/prefix]/<n>`), else `GERRIT_HOST` over
     * `GERRIT_SCHEME` when that is `http`/`https` (the trigger's own `ssh` scheme falls back to `https`).
     */
    fun ciBaseUrl(env: (String) -> String?): String? {
        env(GERRIT_URL)?.let { return it }
        env(GERRIT_CHANGE_URL)?.let { url ->
            val root = if ("/c/" in url) url.substringBefore("/c/") else url.trimEnd('/').substringBeforeLast('/')
            if (root.startsWith("http") && "://" in root) return root
        }
        val host = env(GERRIT_HOST) ?: return null
        val scheme = env(GERRIT_SCHEME)?.takeIf { it == "http" || it == "https" } ?: "https"
        return "$scheme://$host"
    }

    /** The CI build this runs in: a Gerrit Trigger (Jenkins) or any job exporting `GERRIT_URL`. */
    fun detectCi(
        platform: String,
        env: (String) -> String?,
    ): CIEnvironment? {
        val baseUrl = ciBaseUrl(env) ?: return null
        return CIEnvironment(platform, env("GERRIT_PROJECT"), env("GERRIT_CHANGE_NUMBER"), env(GERRIT_TOKEN), baseUrl)
    }
}
