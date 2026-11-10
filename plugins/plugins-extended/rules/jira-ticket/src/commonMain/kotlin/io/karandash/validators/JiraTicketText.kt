package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the JIRA ticket rule's findings in each output language. */
internal enum class JiraTicketText(
    override val en: String,
) : LocalizedText {
    EXPLANATION(
        "Linking JIRA tickets provides traceability between code and requirements.",
    ),
    CUSTOM_NEEDS_PATTERN(
        "format 'custom' requires a pattern; falling back to the default JIRA key shape.",
    ),
    SET_PATTERN(
        "Set pattern in jira-ticket config.",
    ),
    CONFIGURE_PROVIDER(
        "Configure the jira enrichment provider so ticket details are fetched.",
    ),
    SET_URL_STATUS(
        "Set jiraUrl in jira-ticket config to enable status validation.",
    ),
    SET_URL_ASSIGNEE(
        "Set jiraUrl in jira-ticket config to enable assignee validation.",
    ),
}
