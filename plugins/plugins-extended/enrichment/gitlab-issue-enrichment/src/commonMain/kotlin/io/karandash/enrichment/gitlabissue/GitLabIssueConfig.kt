@file:OptIn(ExperimentalSerializationApi::class)

package io.karandash.enrichment.gitlabissue

import io.karandash.providers.common.IssueSubConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
internal data class GitLabIssueConfig(
    val url: String = DEFAULT_BASE_URL,
    @SerialName("project-id") @JsonNames("projectId") val projectId: String = "",
    val token: String? = null,
    val issues: IssueSubConfig = IssueSubConfig(),
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://gitlab.com"
    }
}
