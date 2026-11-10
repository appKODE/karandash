package io.karandash.validators

import io.karandash.api.context.ConditionContext
import io.karandash.conditions.ConditionPredicate
import io.karandash.conditions.PredicateRegistry
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.patterns.PatternDefinition
import io.karandash.patterns.PatternParam
import io.karandash.patterns.PatternRegistry
import io.karandash.sdk.PluginContext
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule
import io.karandash.utils.BoundedCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

class JiraTicketRuleModule : AbstractTypedSingleRuleModule<JiraTicketConfig>(JiraTicketConfig.serializer()) {
    companion object {
        const val RULE_ID = "jiraTicket"

        private const val PARAM_PROJECT = "project"
        private const val KEY_SUFFIX = "-\\d+"
        private const val JIRA_TICKET_PATTERN = "[A-Z]+$KEY_SUFFIX"
        private const val GITHUB_ISSUE_PATTERN = "#\\d+"
        private const val SHORTCUT_TICKET_PATTERN = "sc-\\d+"
        private const val ANY_TICKET_PATTERN = "(?:$JIRA_TICKET_PATTERN|$GITHUB_ISSUE_PATTERN|$SHORTCUT_TICKET_PATTERN)"

        private fun keyedPattern(key: String?): String = if (key != null) "$key$KEY_SUFFIX" else JIRA_TICKET_PATTERN
    }

    override val ruleId: String = RULE_ID
    override val explanation: String =
        "A JIRA ticket reference ensures changes are tracked back to requirements or bugs. " +
            "This creates traceability between code changes and project management."

    override val name: String = "Jira Ticket Rule"
    override val description: String = "Validates that MR title or description references a JIRA ticket."

    /** without `projectKeys` there is nothing to enforce, so the rule runs only when configured. */
    override val defaultEnabled: Boolean = false

    override val supportedEnrichment: List<String> = listOf(EnrichmentKeys.JIRA_TICKETS)

    /** `jira:tickets` data delivered by the pipeline's enrichment re-init; set only in [initialize]. */
    private val tickets = MutableStateFlow<JsonObject?>(null)

    override suspend fun initialize(context: PluginContext): Boolean {
        tickets.value = context.enrichment.get(EnrichmentKeys.JIRA_TICKETS) as? JsonObject
        return true
    }

    override fun doValidateTyped(
        mr: MergeRequest,
        config: JiraTicketConfig
    ): List<Finding> = doValidateTyped(mr, config, null)

    override fun doValidateTyped(
        mr: MergeRequest,
        config: JiraTicketConfig,
        patterns: PatternAwareCompiler?,
    ): List<Finding> = JiraTicketValidator(config, patterns, tickets.value).validate(mr).map { it.withFixedText(JiraTicketText.entries) }

    override fun registerPredicates(registry: PredicateRegistry<ConditionContext>) {
        registry.register(HasTicketPredicate)
    }

    override fun registerPatterns(registry: PatternRegistry) {
        registry.register(
            PatternDefinition(
                name = "jira-ticket",
                description = "JIRA-style ticket reference (e.g. PROJ-123). " +
                    "Use @project:KEY to restrict to a specific project key.",
                params = listOf(
                    PatternParam(name = PARAM_PROJECT, description = "JIRA project key (e.g. MAPS)"),
                ),
                expand = { params -> keyedPattern(params[PARAM_PROJECT]) },
            ),
        )
        registry.register(
            PatternDefinition(
                name = "github-issue",
                description = "GitHub issue reference (e.g. #42)",
                params = emptyList(),
                expand = { GITHUB_ISSUE_PATTERN },
            ),
        )
        registry.register(
            PatternDefinition(
                name = "linear-ticket",
                description = "Linear ticket reference (e.g. ENG-123)",
                params = listOf(
                    PatternParam(name = PARAM_PROJECT, description = "Linear team key (e.g. ENG)"),
                ),
                expand = { params -> keyedPattern(params[PARAM_PROJECT]) },
            ),
        )
        registry.register(
            PatternDefinition(
                name = "shortcut-ticket",
                description = "Shortcut (formerly Clubhouse) ticket reference (e.g. sc-1234)",
                params = emptyList(),
                expand = { SHORTCUT_TICKET_PATTERN },
            ),
        )
        registry.register(
            PatternDefinition(
                name = "youtrack-ticket",
                description = "YouTrack ticket reference (e.g. PROJ-123)",
                params = listOf(
                    PatternParam(name = PARAM_PROJECT, description = "YouTrack project key"),
                ),
                expand = { params -> keyedPattern(params[PARAM_PROJECT]) },
            ),
        )
        registry.register(
            PatternDefinition(
                name = "any-ticket",
                description = "Any supported ticket format: JIRA, GitHub issue, or Shortcut",
                params = emptyList(),
                expand = { ANY_TICKET_PATTERN },
            ),
        )
    }
}

/**
 * Checks whether the MR title contains a ticket reference of the form `PROJECT-\d+`.
 *
 * Usage: `has_ticket(PROJECT)` or `has_ticket(MAPS)` -- no regex required.
 * The project key argument is matched case-insensitively against the ticket prefix.
 */
private object HasTicketPredicate : ConditionPredicate<ConditionContext> {
    private const val PATTERN_CACHE_SIZE = 64

    /** Compiled `\bPROJECT-\d+\b` patterns keyed by uppercased project key. */
    private val patternCache = BoundedCache<String, Regex>(maxEntries = PATTERN_CACHE_SIZE)

    override val name: String = "has_ticket"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val project = args.getOrNull(0)?.uppercase() ?: return false
        val pattern = patternCache.getOrCompute(project) {
            Regex("\\b${Regex.escape(project)}-\\d+\\b", RegexOption.IGNORE_CASE)
        }
        return pattern.containsMatchIn(context.mr.title)
    }
}
