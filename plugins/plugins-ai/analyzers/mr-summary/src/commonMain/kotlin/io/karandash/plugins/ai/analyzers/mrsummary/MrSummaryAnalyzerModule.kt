package io.karandash.plugins.ai.analyzers.mrsummary

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.support.suspendCatching
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.ai.core.AbstractAiAnalyzer
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.ProcessRiskMetrics
import io.karandash.plugins.ai.core.PromptSections
import io.karandash.plugins.ai.core.ReviewUnits
import io.karandash.plugins.ai.core.TriageRisk
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.StringOrListSerializer
import io.karandash.plugins.ai.core.rules.tokensFor
import io.karandash.plugins.ai.prompts.AnalyzerPrompts
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * AI-powered MR summary generator. Produces a concise summary of the merge request
 * changes, highlighting key modifications, risks, and review focus areas.
 *
 * The generated summary is returned as a [Finding] with `aiSummary` metadata,
 * which the comment reporter renders in its AI Suggestions section.
 */
class MrSummaryAnalyzerModule : AbstractAiAnalyzer("mr-summary", "mrSummary") {
    override val id: String = "ai-mr-summary-analyzer"
    override val name: String = "AI MR Summary"
    override val mrLevel: Boolean get() = true
    override val resultSerializer: KSerializer<*> = MrSummaryResult.serializer()
    override val defaultEnabled: Boolean = false
    override val estimatedTokens: Int = 2000
    override val defaultPrompt: String = AnalyzerPrompts.MR_SUMMARY
    override val responseFormatSuffix: String = RESPONSE_FORMAT_SUFFIX
    override val explanation: String =
        "Generates an AI-powered summary of the merge request highlighting key changes, risks, and review focus areas."

    private val json = KarandashJson.Default

    companion object {
        private val RESPONSE_FORMAT_SUFFIX = """
            |ARRAY FIELDS — STRICT:
            |The fields "keyChanges", "risks", and "reviewFocus" are ALWAYS JSON arrays.
            |- Even when you have only ONE item, wrap it in brackets: "reviewFocus": ["only item"]
            |- Never emit a bare string: "reviewFocus": "only item"  <- WRONG, breaks parsing
            |- Never emit an object:     "reviewFocus": {...}       <- WRONG, breaks parsing
            |- If you have nothing to say for risks, use an empty array: "risks": []
            |
            |CORRECT examples:
            |  "reviewFocus": ["Check token refresh logic", "Validate session expiry handling"]
            |  "reviewFocus": ["Check token refresh logic"]
            |  "risks": []
            |WRONG example (do not emit):
            |  "reviewFocus": "Check token refresh logic"
            |
            |Response schema:
            |{
            |  "summary": "short high-level explanation of what this MR changes and why",
            |  "keyChanges": ["first major implementation change", "second major change"],
            |  "risks": ["first potential risk or concern", "second risk"],
            |  "reviewFocus": ["first specific place to verify", "second place to verify"],
            |  "interactions": [{"from": "module-a", "to": "module-b", "message": "what module-a asks of module-b"}],
            |  "suggestedTitle": "clear, descriptive title",
            |  "suggestedDescription": "markdown description with What, Why, How to test and Risks sections"
            |}
            |"interactions" uses the module names from "Review units"; emit [] when the changes stay inside one module.
            |Emit "suggestedTitle" and "suggestedDescription" only when the MR description is missing or poor; omit both otherwise.
        """.trimMargin()
    }

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        val aiConfig = AiRuleConfig.fromPluginConfig(context.config)
        val tokens = aiConfig.tokensFor(featureKey)
        val risk = TriageRisk.measure(context)
        val prompt = buildPrompt(context, risk)
        val systemPrompt = AiRuleAnalyzerUtils.effectivePrompt(
            defaultPrompt,
            context.extraInstructions,
            context.resolvedPrompt,
            responseFormatSuffix,
        )

        val response = context.aiService.complete(
            AiCompletionRequest(
                prompt = prompt,
                systemPrompt = systemPrompt,
                model = context.model,
                maxTokens = AiRuleAnalyzerUtils.resolveMaxResponseTokens(
                    configuredMax = tokens.maxTokens,
                    defaultMax = tokens.defaultMaxTokens,
                    systemPrompt = systemPrompt,
                    userPrompt = prompt,
                    tokens = aiConfig.analyzerTokens,
                ),
                temperature = tokens.temperature,
            ),
        )

        return parseResponse(response.content, response.model, response.totalTokens, context.mr.changes, risk)
    }

    internal fun buildPrompt(
        context: AiAnalyzerContext,
        risk: ProcessRiskMetrics = TriageRisk.of(context),
    ): String {
        val mr = context.mr
        return buildString {
            append(TriageRisk.promptSection(risk))
            appendLine()
            append(PromptSections.mergeRequest(mr, "Author" to mr.author.name.ifEmpty { mr.author.username }))

            appendLine("## Commits (${mr.commits.size} total)")
            for (commit in mr.commits) {
                appendLine("- ${commit.message.lines().first()}")
            }
            appendLine()

            appendLine("## Changed Files (${mr.changes.size} total)")
            for (change in mr.changes) {
                appendLine("- ${change.path} [${change.changeType}] (+${change.additions}/-${change.deletions})")
            }
            appendLine()

            appendLine("## Review units")
            appendLine(ReviewUnits.group(mr.changes).joinToString(", ") { it.name })
            appendLine()

            appendLine("## Diff Previews")
            append(context.diffContext.preparedDiff)
        }
    }

    internal fun parseResponse(
        content: String,
        aiModel: String,
        tokensUsed: Int,
        changes: List<FileChange> = emptyList(),
        risk: ProcessRiskMetrics? = null,
    ): List<Finding> {
        val result = suspendCatching {
            json.decodeFromString<MrSummaryResult>(AiRuleAnalyzerUtils.extractJson(content))
        }.getOrElse { cause ->
            return when (cause) {
                is SerializationException, is IllegalArgumentException -> {
                    AiRuleAnalyzerUtils.parseErrorFinding(
                        ruleId = analyzerId,
                        content = content,
                        aiModel = aiModel,
                        tokensUsed = tokensUsed,
                        prefix = "AI MR summary generation",
                        parseException = cause.message,
                    )
                }

                else -> {
                    throw cause
                }
            }
        }

        val explanation = buildString {
            appendLine(result.summary)
            appendSection("Key changes", result.keyChanges)
            appendSection("Risks", listOfNotNull(risk?.let(TriageRisk::line)) + result.risks)
            appendSection("Review focus", result.reviewFocus)
        }.trim()

        val walkthrough = walkthrough(changes, result.interactions)
        val summary = Finding(
            severity = Severity.INFO,
            message = explanation,
            rule = analyzerId,
            explanation = null,
            metadata = mapOf(AiFindingMetadataKeys.IS_MR_SUMMARY to "true") + risk?.let(TriageRisk::metadata).orEmpty() +
                listOfNotNull(walkthrough?.let { WellKnownMetadataKeys.WALKTHROUGH to it }),
        )
        return listOfNotNull(summary, describedFinding(result))
    }

    /** The generate-description-shaped finding, so `mr-summary` alone feeds the suggested description consumers. */
    private fun describedFinding(result: MrSummaryResult): Finding? {
        val title = result.suggestedTitle?.takeIf { it.isNotBlank() }
        val description = result.suggestedDescription?.takeIf { it.isNotBlank() }
        if (title == null && description == null) return null
        return Finding(
            severity = Severity.INFO,
            message = "AI-generated description available." + title?.let { " Suggested title: \"$it\"" }.orEmpty(),
            rule = analyzerId,
            metadata = mapOf(AiFindingMetadataKeys.IS_GENERATED_DESCRIPTION to "true") +
                listOfNotNull(
                    title?.let { AiFindingMetadataKeys.SUGGESTED_TITLE to it },
                    description?.let { AiFindingMetadataKeys.SUGGESTED_DESCRIPTION to it },
                ),
        )
    }

    private fun StringBuilder.appendSection(
        title: String,
        items: List<String>,
    ) {
        if (items.isEmpty()) return
        appendLine()
        appendLine("**$title:**")
        items.forEach { appendLine("- $it") }
    }
}

@Serializable
internal data class MrSummaryResult(
    val summary: String,
    @Serializable(with = StringOrListSerializer::class)
    val keyChanges: List<String> = emptyList(),
    @Serializable(with = StringOrListSerializer::class)
    val risks: List<String> = emptyList(),
    @Serializable(with = StringOrListSerializer::class)
    val reviewFocus: List<String> = emptyList(),
    val interactions: List<MrSummaryInteraction> = emptyList(),
    val suggestedTitle: String? = null,
    val suggestedDescription: String? = null,
)
