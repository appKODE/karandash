package io.karandash.enrichment.jira

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Raw REST API v3 issue response from `GET /rest/api/3/issue/{key}`. */
@Serializable
internal data class JiraIssueResponse(
    val id: String = "",
    val key: String = "",
    val fields: JiraIssueFields = JiraIssueFields(),
    /** Value of the configured `acceptance-criteria.field` custom field; filled by the client, not by Jira. */
    val acceptanceField: JsonElement? = null,
)

@Serializable
internal data class JiraIssueFields(
    val summary: String = "",
    val description: JsonElement? = null,
    @SerialName("issuetype") val issueType: JiraNamedValue? = null,
    val priority: JiraNamedValue? = null,
    val status: JiraStatus? = null,
    val labels: List<String> = emptyList(),
    val assignee: JiraUser? = null,
    @SerialName("customfield_10014") val epicKey: String? = null,
    @SerialName("issuelinks") val issueLinks: List<JiraIssueLink> = emptyList(),
)

@Serializable
internal data class JiraNamedValue(
    val name: String = ""
)

@Serializable
internal data class JiraStatus(
    val name: String = "",
    @SerialName("statusCategory") val category: JiraNamedValue? = null,
)

@Serializable
internal data class JiraUser(
    @SerialName("displayName") val displayName: String = "",
)

@Serializable
internal data class JiraIssueLink(
    val type: JiraIssueLinkType = JiraIssueLinkType(),
    @SerialName("inwardIssue") val inwardIssue: JiraLinkedIssueRef? = null,
    @SerialName("outwardIssue") val outwardIssue: JiraLinkedIssueRef? = null,
)

@Serializable
internal data class JiraIssueLinkType(
    val name: String = "",
    val inward: String = "",
    val outward: String = "",
)

@Serializable
internal data class JiraLinkedIssueRef(
    val key: String = "",
    val fields: JiraLinkedIssueRefFields? = null,
)

@Serializable
internal data class JiraLinkedIssueRefFields(
    val summary: String = "",
    val status: JiraStatus? = null,
)

/** Domain model for a Jira ticket's key fields (stored in [EnrichmentContext]). */
@Serializable
data class JiraTicketDetails(
    val key: String,
    val summary: String,
    val description: String,
    val type: String,
    val priority: String,
    val status: String,
    val labels: List<String>,
    val assignee: String?,
    val epicKey: String?,
)

/** Domain model for a Jira linked issue. */
@Serializable
data class JiraLinkedIssue(
    val relation: String,
    val targetKey: String,
    val summary: String,
    val status: String,
    val remoteUrl: String?,
)

/** A Jira remote link (`/issue/{key}/remotelink`), e.g. a Confluence page attached to an issue. */
@Serializable
internal data class JiraRemoteLink(
    val `object`: JiraRemoteLinkObject? = null,
)

@Serializable
internal data class JiraRemoteLinkObject(
    val url: String? = null,
    val title: String? = null,
)
