package io.karandash.enrichment.github

import io.karandash.providers.common.IssueSubConfig
import kotlinx.serialization.Serializable

@Serializable
internal data class GitHubIssueConfig(
    val url: String = DEFAULT_BASE_URL,
    val owner: String = "",
    val repo: String = "",
    val token: String? = null,
    val issues: IssueSubConfig = IssueSubConfig(),
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.github.com"
    }
}
