package io.karandash.platforms.github

import io.karandash.crypto.Jwt
import io.karandash.sdk.content.ContentSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * `platforms.github.auth`: `type: token` (the default, `token` / `GITHUB_TOKEN`) or `type: app` with the GitHub
 * App's [appId], the [installationId] to act as, and its [privateKey] PEM (inline, `${ENV}`, or `{ file: }` relative
 * to the config file).
 */
@Serializable
data class GitHubAuthConfig(
    val type: String = TYPE_TOKEN,
    val appId: String? = null,
    val installationId: String? = null,
    val privateKey: ContentSource? = null,
) {
    companion object {
        const val TYPE_TOKEN = "token"
        const val TYPE_APP = "app"
    }
}

/** A GitHub App acting as one installation. [toString] leaves the key out. */
@Serializable
data class GitHubAppCredentials(
    val appId: String,
    val installationId: String,
    val privateKeyPem: String,
) {
    override fun toString(): String = "GitHubAppCredentials(appId=$appId, installationId=$installationId, privateKeyPem=***)"
}

/** `POST /app/installations/{id}/access_tokens` response. [toString] leaves the token out. */
@Serializable
data class GitHubInstallationToken(
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
) {
    override fun toString(): String = "GitHubInstallationToken(token=***, expiresAt=$expiresAt)"
}

/**
 * The installation token of a GitHub App: exchanged for an RS256 JWT on first use, reused until [REFRESH_MARGIN]
 * before `expires_at`, dropped on a 401 so the next request gets a new one.
 */
internal class GitHubAppTokenCache(
    private val app: GitHubAppCredentials,
    private val clock: Clock,
    private val exchange: suspend (installationId: String, jwt: String) -> GitHubInstallationToken,
) {
    private class Cached(
        val token: String,
        val refreshAt: Instant,
    )

    private val mutex = Mutex()
    private val cached = MutableStateFlow<Cached?>(null)

    /** The current installation token, or null before the first [ensureFresh]. */
    fun current(): String? = cached.value?.token

    suspend fun ensureFresh() {
        if (isFresh(cached.value)) return
        mutex.withLock {
            if (isFresh(cached.value)) return
            val issued = exchange(app.installationId, appJwt(clock.now()))
            cached.value = Cached(issued.token, Instant.parse(issued.expiresAt) - REFRESH_MARGIN)
        }
    }

    /** Drops the cached token; always `true`, so a 401 is retried once with a new token. */
    fun invalidate(): Boolean {
        cached.value = null
        return true
    }

    /** `iat` a minute back for clock drift, `exp` 9 minutes ahead (GitHub caps it at 10), `iss` the app id. */
    internal fun appJwt(now: Instant): String =
        Jwt.signRs256(
            buildJsonObject {
                put("iat", (now - CLOCK_DRIFT).epochSeconds)
                put("exp", (now + JWT_LIFETIME).epochSeconds)
                put("iss", app.appId)
            },
            app.privateKeyPem,
        )

    private fun isFresh(token: Cached?): Boolean = token != null && clock.now() < token.refreshAt

    companion object {
        val REFRESH_MARGIN = 5.minutes
        val CLOCK_DRIFT = 60.seconds
        val JWT_LIFETIME = 9.minutes
    }
}
