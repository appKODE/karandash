package io.karandash.registry

import io.karandash.http.HttpAuth
import kotlinx.serialization.Serializable

/**
 * Authentication configuration for a plugin registry.
 *
 * @property token Authentication token (PAT, deploy token, etc.).
 * @property headerName HTTP header name for the token. Defaults to "Authorization" with "Bearer" prefix.
 *   Set to "PRIVATE-TOKEN" for GitLab personal access tokens (no prefix added).
 */
@Serializable
data class RegistryAuth(
    val token: String,
    val headerName: String = DEFAULT_HEADER_NAME,
) {
    companion object {
        const val DEFAULT_HEADER_NAME = "Authorization"
        const val GITLAB_HEADER_NAME = "PRIVATE-TOKEN"
    }

    /** This token as the shared [HttpAuth]: Bearer for `Authorization`, the bare token for any other header. */
    fun toHttpAuth(): HttpAuth = if (headerName == DEFAULT_HEADER_NAME) HttpAuth.Bearer(token) else HttpAuth.CustomHeader(headerName, token)

    /** The header name and value pair for HTTP requests (see [toHttpAuth]). */
    fun toHeader(): Pair<String, String> = toHttpAuth().let { it.headerName to it.headerValue() }
}
