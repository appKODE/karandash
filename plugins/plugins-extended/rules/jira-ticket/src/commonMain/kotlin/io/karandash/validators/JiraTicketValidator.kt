package io.karandash.validators

import io.karandash.helpers.RegexHelper
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.codeSpan
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.patterns.PatternFix
import io.karandash.patterns.TicketFormat
import io.karandash.utils.finding.FindingFactory
import io.karandash.utils.toEscapedAlternation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class JiraTicketValidator(
    private val config: JiraTicketConfig,
    patterns: PatternAwareCompiler? = null,
    /** `jira:tickets` enrichment data (ticket key to details), or null when the jira provider did not run. */
    private val tickets: JsonObject? = null,
) {
    private companion object {
        val DEFAULT_EXPLANATION = JiraTicketText.EXPLANATION.en
        const val SUBJECT = "ticket"

        fun buildPatternFromConfig(config: JiraTicketConfig): String =
            when (config.format) {
                TicketFormat.GITHUB -> {
                    "#\\d+"
                }

                TicketFormat.SHORTCUT -> {
                    "sc-\\d+"
                }

                // For JIRA-family formats, always use a general pattern so that wrong-key
                // tickets are detected; project key validation is a separate check (line 78).
                TicketFormat.JIRA, TicketFormat.LINEAR, TicketFormat.YOUTRACK, null -> {
                    "[A-Z]+-\\d+"
                }

                TicketFormat.CUSTOM -> {
                    "[A-Z]+-\\d+"
                }
            }
    }

    private val effectiveKeys: List<String> = config.effectiveProjectKeys()
    private val compiledPattern: String = config.pattern ?: buildPatternFromConfig(config)
    private val compiledRegex: Regex?
    private val compilationError: Finding?

    init {
        val initFindings = mutableListOf<Finding>()
        compiledRegex = RegexHelper.tryCompile(patterns, compiledPattern, JiraTicketRuleModule.RULE_ID, initFindings)
        compilationError = initFindings.firstOrNull()
    }

    fun validate(mr: MergeRequest): List<Finding> = configWarnings() + validateTickets(mr)

    /** `format: custom` has no built-in shape, so it needs an explicit `pattern`. */
    private fun configWarnings(): List<Finding> =
        if (config.format == TicketFormat.CUSTOM && config.pattern == null) {
            listOf(
                FindingFactory.warn(
                    rule = JiraTicketRuleModule.RULE_ID,
                    message = "format 'custom' requires a pattern; falling back to the default JIRA key shape.",
                    suggestion = "Set pattern in jira-ticket config.",
                    explanation = DEFAULT_EXPLANATION,
                )
            )
        } else {
            emptyList()
        }

    private fun validateTickets(mr: MergeRequest): List<Finding> {
        val regex = compiledRegex ?: return listOfNotNull(compilationError)

        val ticketMatch = findTicketMatches(regex, mr).let { all ->
            all.firstOrNull { effectiveKeys.isEmpty() || it.value.substringBefore("-") in effectiveKeys } ?: all.firstOrNull()
        }
            ?: return listOf(
                FindingFactory.error(
                    rule = JiraTicketRuleModule.RULE_ID,
                    message = "No ${"${formatLabel()} ticket".trimStart()} found in MR ${checkInLabel()}.",
                    suggestion = "Add a ticket reference such as ${codeSpan(exampleTicket())} to the ${checkInLabel()}.",
                    explanation = DEFAULT_EXPLANATION,
                    metadata = formatMetadata(),
                )
            )

        val ticket = ticketMatch.value
        val projectKey = ticket.substringBefore("-")

        return buildList {
            if (effectiveKeys.isNotEmpty() && projectKey !in effectiveKeys) {
                add(
                    FindingFactory.error(
                        rule = JiraTicketRuleModule.RULE_ID,
                        message = "Invalid ${formatLabel()} project key: $projectKey.",
                        suggestion = "Use a ticket of an allowed project, e.g. " +
                            "${codeSpan(effectiveKeys.first() + "-" + ticket.substringAfter("-"))} instead of ${codeSpan(ticket)}.",
                        explanation = DEFAULT_EXPLANATION,
                        metadata = formatMetadata(),
                    )
                )
            }

            val details = tickets?.get(ticket) as? JsonObject
            if (details != null) {
                addAll(ticketDetailFindings(ticket, details))
                return@buildList
            }
            if ((!config.validateStatus.isNullOrEmpty() || config.validateAssignee) && config.jiraUrl != null) {
                add(
                    FindingFactory.warn(
                        rule = JiraTicketRuleModule.RULE_ID,
                        message = "No Jira data for ticket $ticket. Status/assignee validation skipped.",
                        suggestion = "Configure the jira enrichment provider so ticket details are fetched.",
                        explanation = DEFAULT_EXPLANATION,
                    )
                )
            }

            if (!config.validateStatus.isNullOrEmpty() && config.jiraUrl == null) {
                add(
                    FindingFactory.warn(
                        rule = JiraTicketRuleModule.RULE_ID,
                        message = "validateStatus is configured but jiraUrl is not set. " +
                            "Status validation skipped for ticket $ticket.",
                        suggestion = "Set jiraUrl in jira-ticket config to enable status validation.",
                        explanation = DEFAULT_EXPLANATION
                    )
                )
            }

            if (config.validateAssignee && config.jiraUrl == null) {
                add(
                    FindingFactory.warn(
                        rule = JiraTicketRuleModule.RULE_ID,
                        message = "validateAssignee is enabled but jiraUrl is not set. " +
                            "Assignee validation skipped for ticket $ticket.",
                        suggestion = "Set jiraUrl in jira-ticket config to enable assignee validation.",
                        explanation = DEFAULT_EXPLANATION
                    )
                )
            }
        }
    }

    private fun ticketDetailFindings(
        ticket: String,
        details: JsonObject,
    ): List<Finding> =
        buildList {
            val allowed = config.validateStatus.orEmpty()
            val status = (details["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (allowed.isNotEmpty() && allowed.none { it.equals(status, ignoreCase = true) }) {
                add(
                    FindingFactory.error(
                        rule = JiraTicketRuleModule.RULE_ID,
                        message = "Ticket $ticket has status '$status', which is not allowed.",
                        suggestion = "Move $ticket to one of: ${allowed.joinToString(", ")}.",
                        explanation = DEFAULT_EXPLANATION,
                    )
                )
            }
            val assignee = (details["assignee"] as? JsonPrimitive)?.contentOrNull
            if (config.validateAssignee && assignee.isNullOrBlank()) {
                add(
                    FindingFactory.error(
                        rule = JiraTicketRuleModule.RULE_ID,
                        message = "Ticket $ticket has no assignee.",
                        suggestion = "Assign $ticket before merging.",
                        explanation = DEFAULT_EXPLANATION,
                    )
                )
            }
        }

    /** Every ticket-shaped match in the checked text, so a valid key anywhere satisfies the rule. */
    private fun findTicketMatches(
        regex: Regex,
        mr: MergeRequest
    ): List<MatchResult> {
        val inTitle = regex.findAll(mr.title).toList()
        val inDescription = mr.description?.let { regex.findAll(it).toList() }.orEmpty()
        return when (config.checkIn) {
            JiraTicketConfig.CHECK_IN_TITLE -> inTitle
            JiraTicketConfig.CHECK_IN_DESCRIPTION -> inDescription
            else -> inTitle + inDescription
        }
    }

    /** Ticket system named in messages; empty for [TicketFormat.CUSTOM], which has no system name. */
    private fun formatLabel(): String =
        when (config.format) {
            TicketFormat.JIRA, null -> "JIRA"
            TicketFormat.GITHUB -> "GitHub"
            TicketFormat.LINEAR -> "Linear"
            TicketFormat.SHORTCUT -> "Shortcut"
            TicketFormat.YOUTRACK -> "YouTrack"
            TicketFormat.CUSTOM -> ""
        }

    private fun checkInLabel(): String =
        when (config.checkIn) {
            JiraTicketConfig.CHECK_IN_TITLE -> "title"
            JiraTicketConfig.CHECK_IN_DESCRIPTION -> "description"
            else -> "title or description"
        }

    /** A ticket in the configured shape: the first project key, the format's own example, or the pattern's example. */
    private fun exampleTicket(): String =
        when {
            effectiveKeys.isNotEmpty() -> "${effectiveKeys.first()}-123"
            config.pattern != null -> PatternFix.of(config.pattern, "", SUBJECT, diagnose = false).value ?: "PROJ-123"
            config.format == TicketFormat.GITHUB -> "#42"
            config.format == TicketFormat.SHORTCUT -> "sc-1234"
            else -> "PROJ-123"
        }

    /** The ticket format (with the allowed project keys when there are any) for the fix hint. */
    private fun formatMetadata(): Map<String, String> {
        val format = if (config.pattern == null && effectiveKeys.isNotEmpty()) {
            "(${effectiveKeys.toEscapedAlternation()})-\\d+"
        } else {
            compiledPattern
        }
        return PatternFix.of(format, "", SUBJECT, diagnose = false).metadata() + (WellKnownMetadataKeys.PATTERN to format)
    }
}
