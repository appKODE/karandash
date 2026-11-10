@file:OptIn(ExperimentalSerializationApi::class)

package io.karandash.enrichment.jira

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
internal data class JiraSubBlockConfig(
    val enabled: Boolean = true,
)

@Serializable
internal data class JiraTicketsSubConfig(
    val enabled: Boolean = true,
    @SerialName("max-per-mr") @JsonNames("maxPerMr") val maxPerMr: Int = DEFAULT_MAX_PER_MR,
) {
    companion object {
        const val DEFAULT_MAX_PER_MR = 10
    }
}

@Serializable
internal data class JiraAcSubConfig(
    val enabled: Boolean = true,
    val field: String? = null,
)

@Serializable
internal data class JiraConfig(
    val url: String = "",
    val token: String? = null,
    val email: String? = null,
    @SerialName("ticket-keys") @JsonNames("ticketKeys") val ticketKeys: JiraSubBlockConfig = JiraSubBlockConfig(),
    val tickets: JiraTicketsSubConfig = JiraTicketsSubConfig(),
    @SerialName("acceptance-criteria") @JsonNames("acceptanceCriteria") val acceptanceCriteria: JiraAcSubConfig = JiraAcSubConfig(),
    @SerialName("linked-issues") @JsonNames("linkedIssues") val linkedIssues: JiraSubBlockConfig = JiraSubBlockConfig(),
    @SerialName(
        "max-requests-per-minute"
    ) @JsonNames("maxRequestsPerMinute") val maxRequestsPerMinute: Int = DEFAULT_MAX_REQUESTS_PER_MINUTE,
) {
    companion object {
        const val DEFAULT_MAX_REQUESTS_PER_MINUTE = 60
    }
}
