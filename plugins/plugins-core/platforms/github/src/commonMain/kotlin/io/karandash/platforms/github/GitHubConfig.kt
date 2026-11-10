package io.karandash.platforms.github

import io.karandash.api.PlatformServiceConfig
import kotlinx.serialization.Serializable

/**
 * Typed configuration for GitHub provider, mutator, and comment-poster modules.
 *
 * [url] is the canonical API base URL key (it matches the other platforms). [baseUrl] stays as an alias
 * and warns when set. When both are set, [url] wins. [auth] selects token or GitHub App auth.
 */
@Serializable
data class GitHubConfig(
    val url: String = "https://api.github.com",
    val token: String? = null,
    val baseUrl: String? = null,
    val auth: GitHubAuthConfig? = null,
) : PlatformServiceConfig {
    companion object
}
