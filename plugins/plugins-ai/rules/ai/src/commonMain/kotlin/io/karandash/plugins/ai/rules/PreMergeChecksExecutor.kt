package io.karandash.plugins.ai.rules

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiService
import io.karandash.api.ai.AiTokenBudget
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.support.rethrowCancellation
import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.PresentationKeys
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.config.PreMergeCheck
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.schema.JsonSchemaGenerator
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.security.TextRedactor
import io.karandash.utils.GlobMatcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pre-merge checklist (`ai.checks`, `ai.checks-ticket-compliance`): every check that applies goes to the
 * model in ONE call, which answers pass, fail or n/a with a one-line reason per check. Each check becomes a
 * row finding routed to its own report section; a failing `mode: error` check is an ERROR, so it fails the run.
 *
 * The prompt (instructions, ticket titles, criteria and bodies, diff) goes through [TextRedactor]; the row
 * labels in the report keep the ticket title as written.
 *
 * Fails soft: a provider error or an unreadable reply makes the asked rows n/a, never a throw; such a row of a
 * `mode: error` check is a WARNING, of a `warn` check INFO.
 */
internal object PreMergeChecksExecutor {
    /** Token estimate the dispatcher's budget gate checks before the checklist runs. */
    const val ESTIMATED_TOKENS = 1500
    const val SECTION = "pre-merge-checklist"
    private const val SECTION_ORDER = "0"

    /** Upper bound on the reply tokens one checklist call may ask for, however many rows it carries. */
    const val MAX_REPLY_TOKENS = 4000

    /** Ticket rows asked at most; the rest become one n/a row naming how many were left out. */
    const val MAX_TICKET_ROWS = 20
    private const val REPLY_TOKENS_BASE = 300
    private const val REPLY_TOKENS_PER_ROW = 80
    private const val TEMPERATURE = 0.0
    private const val TICKET_TEXT_MAX = 1000
    private const val PASS = "pass"
    private const val FAIL = "fail"

    private val responseSchema = JsonSchemaGenerator.fromDescriptor(ChecksReply.serializer().descriptor)

    /**
     * What one checklist run needs from the dispatch. [language] is `report.language` (via `ai.responseLanguage`):
     * the fixed row texts and the section title follow it; check names and model reasons never do.
     *
     * Not @Serializable: carries the run's live [EnrichmentContext].
     */
    data class Input(
        val checks: List<PreMergeCheck>,
        val ticketCompliance: Boolean,
        val mr: MergeRequest,
        val diff: String,
        val enrichment: EnrichmentContext?,
        val model: String,
        val language: String? = null,
    )

    private class Row(
        val name: String,
        val instruction: String,
        val blocking: Boolean,
        val skipReason: String? = null,
        val ticket: Boolean = false,
    )

    /** One row finding per check, in config order, then the ticket rows and their ticket-compliance summary. */
    suspend fun execute(
        input: Input,
        aiService: AiService?,
        budget: AiTokenBudget,
        logger: Logger,
    ): List<Finding> {
        val lang = input.language
        val tickets = if (input.ticketCompliance) ticketRows(input.enrichment, lang) else emptyList()
        val rows = input.checks.map { configured(it, input.mr, lang) } + tickets
        val asked = rows.filter { it.skipReason == null }
        if (asked.isEmpty()) return rows.map { finding(it, null, it.skipReason.orEmpty(), lang) }
        if (aiService == null) logger.warn("[ai] pre-merge checks: no AI service, checks not evaluated")
        val verdicts = aiService?.let { ask(asked, input, it, budget, logger) }
        val unanswered = (if (aiService == null) PreMergeChecksText.NO_SERVICE else PreMergeChecksText.CALL_FAILED).of(lang)
        val statuses = asked.withIndex().associate { (i, row) ->
            row to verdicts
                ?.getOrNull(i)
                ?.status
                ?.trim()
                ?.lowercase()
        }
        return rows.map { row ->
            val i = asked.indexOf(row)
            when {
                i < 0 -> finding(row, null, row.skipReason.orEmpty(), lang)

                verdicts == null -> notEvaluated(row, unanswered, lang)

                else -> verdicts[i]?.let { finding(row, it.status, it.reason, lang) }
                    ?: notEvaluated(row, PreMergeChecksText.NO_VERDICT.of(lang), lang)
            }
        } + listOfNotNull(ticketSummary(statuses.filterKeys { it.ticket }, lang))
    }

    /**
     * the ticket-compliance block of the markdown summary, from the ticket rows the model answered pass or
     * fail; null when it answered none of them.
     */
    private fun ticketSummary(
        ticketStatuses: Map<Row, String?>,
        lang: String?,
    ): Finding? {
        val met = ticketStatuses.filterValues { it == PASS }.keys.map { it.name }
        val missing = ticketStatuses.filterValues { it == FAIL }.keys.map { it.name }
        val judged = met.size + missing.size
        if (judged == 0) return null
        val message = PreMergeChecksText.TICKET_SUMMARY
            .of(lang)
            .replace("%d", met.size.toString())
            .replace("%m", judged.toString())
        return Finding(
            severity = if (missing.isEmpty()) Severity.INFO else Severity.WARNING,
            message = message,
            rule = AiRuleIds.CHECKS,
            metadata = mapOf(
                WellKnownMetadataKeys.IS_TICKET_COMPLIANCE to "true",
                WellKnownMetadataKeys.COMPLIANCE_SCORE to (met.size.toDouble() / judged).toString(),
                WellKnownMetadataKeys.FULFILLED_REQUIREMENTS to met.joinToString(";") { it.replace(";", ",") },
                WellKnownMetadataKeys.MISSING_REQUIREMENTS to missing.joinToString(";") { it.replace(";", ",") },
            ),
        )
    }

    private fun configured(
        check: PreMergeCheck,
        mr: MergeRequest,
        lang: String?,
    ): Row {
        val applies = check.paths.isEmpty() || mr.changes.any { c -> check.paths.any { GlobMatcher.matches(c.path, it) } }
        return Row(check.name, check.instruction, check.blocking, if (applies) null else PreMergeChecksText.NO_PATH_MATCH.of(lang))
    }

    /** Acceptance-criterion rows for Jira tickets that have them; one title row for every other linked ticket. */
    private fun ticketRows(
        enrichment: EnrichmentContext?,
        lang: String?,
    ): List<Row> {
        val criteria = objectOf(enrichment, EnrichmentKeys.JIRA_ACCEPTANCE_CRITERIA)
            .mapValues { (_, v) ->
                (v as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
            }.filterValues { it.isNotEmpty() }
        val acRows = criteria.flatMap { (key, bullets) ->
            bullets.map {
                Row("$key: $it", "The change meets this acceptance criterion of ticket $key: $it", blocking = false, ticket = true)
            }
        }
        val titled = titledRows(objectOf(enrichment, EnrichmentKeys.JIRA_TICKETS).filterKeys { it !in criteria }, "", "summary") +
            titledRows(objectOf(enrichment, EnrichmentKeys.GITHUB_ISSUES), "#", "title") +
            titledRows(objectOf(enrichment, EnrichmentKeys.GITLAB_ISSUES), "#", "title")
        val all = acRows + titled
        val ticketRow = PreMergeChecksText.TICKET_ROW.of(lang)
        if (all.isEmpty()) return listOf(Row(ticketRow, "", blocking = false, skipReason = PreMergeChecksText.NO_TICKET.of(lang)))
        if (all.size <= MAX_TICKET_ROWS) return all
        val skipped = PreMergeChecksText.MORE_TICKETS
            .of(lang)
            .replace("%d", (all.size - MAX_TICKET_ROWS).toString())
            .replace("%m", MAX_TICKET_ROWS.toString())
        return all.take(MAX_TICKET_ROWS) + Row(ticketRow, "", blocking = false, skipReason = skipped)
    }

    private fun titledRows(
        tickets: Map<String, JsonElement>,
        prefix: String,
        titleKey: String,
    ): List<Row> =
        tickets.mapNotNull { (key, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val title = text(obj, titleKey) ?: return@mapNotNull null
            val body = (text(obj, "description") ?: text(obj, "body")).orEmpty().take(TICKET_TEXT_MAX)
            Row(
                "$prefix$key: $title",
                "The change does what ticket $prefix$key asks: $title\n$body".trim(),
                blocking = false,
                ticket = true
            )
        }

    private fun objectOf(
        enrichment: EnrichmentContext?,
        id: String,
    ): JsonObject = enrichment?.get(id) as? JsonObject ?: JsonObject(emptyMap())

    private fun text(
        obj: JsonObject,
        key: String,
    ): String? = (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    /** Verdict per asked row (null where the reply has none), or null when the call or its reply failed. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun ask(
        asked: List<Row>,
        input: Input,
        aiService: AiService,
        budget: AiTokenBudget,
        logger: Logger,
    ): List<CheckResult?>? =
        try {
            val reply = aiService.complete(
                AiCompletionRequest(
                    prompt = TextRedactor.redact(buildPrompt(asked, input)),
                    systemPrompt = LanguagePromptOverlays.withOutputLanguage(SYSTEM_PROMPT, input.language),
                    model = input.model,
                    maxTokens = (REPLY_TOKENS_BASE + REPLY_TOKENS_PER_ROW * asked.size).coerceAtMost(MAX_REPLY_TOKENS),
                    temperature = TEMPERATURE,
                    responseSchema = responseSchema,
                ),
            )
            budget.recordSpend(reply.totalTokens)
            val results = KarandashJson.Default
                .decodeFromString<ChecksReply>(AiRuleAnalyzerUtils.extractJson(reply.content))
                .results
            asked.indices.map { i -> results.firstOrNull { it.index == i } }
        } catch (e: Exception) {
            // boundary: checklist LLM call or its untrusted reply; rows become n/a
            e.rethrowCancellation()
            logger.warn("[ai] pre-merge checks not evaluated: ${e.message}")
            null
        }

    private fun buildPrompt(
        asked: List<Row>,
        input: Input,
    ): String =
        buildString {
            appendLine("## Checks")
            asked.forEachIndexed { i, row -> appendLine("$i. ${row.name}: ${row.instruction}") }
            appendLine()
            appendLine("## Merge Request: ${input.mr.title}")
            appendLine()
            appendLine("## Diff")
            append(input.diff)
        }

    /** An `error` check nobody evaluated is a WARNING, so a silent outage stays visible; a `warn` check stays INFO. */
    private fun notEvaluated(
        row: Row,
        reason: String,
        lang: String?,
    ): Finding = finding(row, null, reason, lang).let { if (row.blocking) it.copy(severity = Severity.WARNING) else it }

    private fun finding(
        row: Row,
        status: String?,
        reason: String,
        lang: String?,
    ): Finding {
        val normalized = status?.trim()?.lowercase()
        val (mark, severity) = when (normalized) {
            PASS -> "✅" to Severity.INFO
            FAIL -> "❌" to if (row.blocking) Severity.ERROR else Severity.WARNING
            else -> "➖" to Severity.INFO
        }
        return Finding(
            severity = severity,
            message = if (reason.isBlank()) "$mark ${row.name}" else "$mark ${row.name} — ${reason.trim()}",
            rule = AiRuleIds.CHECKS,
            metadata = mapOf(
                PresentationKeys.SECTION to SECTION,
                PresentationKeys.SECTION_TITLE to PreMergeChecksText.SECTION_TITLE.of(lang),
                PresentationKeys.SECTION_ORDER to SECTION_ORDER,
            ),
        )
    }

    private val SYSTEM_PROMPT = """
You check a merge request against a numbered list of pre-merge checks. For each check answer from the diff:
"pass" when the change satisfies it, "fail" when the change violates it, "n/a" when the change does not
touch what the check is about. Give the status first, then a one-line reason.

Respond with ONLY valid JSON:
{"results": [{"index": 0, "status": "pass", "reason": "..."}]}
    """.trimIndent()
}

@Serializable
private data class ChecksReply(
    val results: List<CheckResult> = emptyList(),
)

@Serializable
private data class CheckResult(
    val index: Int,
    val status: String,
    val reason: String = "",
)
