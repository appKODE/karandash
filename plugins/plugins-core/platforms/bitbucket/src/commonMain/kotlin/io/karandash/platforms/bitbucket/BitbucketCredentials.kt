package io.karandash.platforms.bitbucket

import io.karandash.models.support.KarandashJson
import io.karandash.providers.common.CredentialsSupport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object BitbucketCredentials {
    @Serializable
    data class Resolved(
        val token: String?,
        val baseUrl: String = BitbucketConfig.DEFAULT_URL,
        val username: String? = null,
    )

    /** Bitbucket API root override, e.g. a proxy in front of Bitbucket Cloud. */
    const val API_URL = "BITBUCKET_API_URL"

    private const val BITBUCKET_TOKEN = "BITBUCKET_TOKEN"
    private const val BITBUCKET_ACCESS_TOKEN = "BITBUCKET_ACCESS_TOKEN"
    private const val BITBUCKET_USERNAME = "BITBUCKET_USERNAME"

    /**
     * Resolve Bitbucket credentials from config and environment.
     *
     * Precedence:
     * - Token: config.token > BITBUCKET_TOKEN > BITBUCKET_ACCESS_TOKEN (optional: anonymous reads of public
     *   repositories; `checkAuth` reports a missing or bad token)
     * - Username (app-password Basic auth): config.username > BITBUCKET_USERNAME
     * - URL: config.url (alias baseUrl) > BITBUCKET_API_URL > Bitbucket Cloud
     */
    fun resolve(
        config: JsonElement,
        env: (String) -> String?
    ): Resolved {
        val cfg = CredentialsSupport.decodeOrDefault(config, BitbucketConfig.serializer()) { BitbucketConfig() }

        val token = CredentialsSupport.firstNonBlank(cfg.token, env, BITBUCKET_TOKEN, BITBUCKET_ACCESS_TOKEN)
        val urlSet = config is JsonObject && (config.containsKey("url") || config.containsKey("baseUrl"))
        val baseUrl = if (urlSet) cfg.url else env(API_URL) ?: cfg.url

        return Resolved(
            token = token,
            baseUrl = baseUrl.trimEnd('/'),
            username = CredentialsSupport.firstNonBlank(cfg.username, env, BITBUCKET_USERNAME)
        )
    }

    fun supportedEnvVars(): Set<String> = setOf(BITBUCKET_TOKEN, BITBUCKET_ACCESS_TOKEN, BITBUCKET_USERNAME, API_URL)
}
