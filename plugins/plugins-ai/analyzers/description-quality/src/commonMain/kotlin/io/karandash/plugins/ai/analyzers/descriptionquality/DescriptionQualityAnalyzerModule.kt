package io.karandash.plugins.ai.analyzers.descriptionquality

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.support.suspendCatching
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AbstractAiAnalyzer
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.formatDecimals
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.tokensFor
import io.karandash.plugins.ai.prompts.AnalyzerPrompts
import io.karandash.utils.finding.FindingFactory
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * AI-powered description quality analyzer.
 *
 * Evaluates the clarity, completeness, and usefulness of a merge request description
 * using an AI completion call. Returns a warning finding when significant quality
 * issues are detected.
 */
class DescriptionQualityAnalyzerModule : AbstractAiAnalyzer(ANALYZER_ID, "descriptionQuality") {
    override val id: String get() = ID
    override val name: String get() = "AI Description Quality"
    override val mrLevel: Boolean get() = true
    override val resultSerializer: KSerializer<*> = DescriptionResult.serializer()
    override val defaultEnabled: Boolean get() = false
    override val estimatedTokens: Int get() = 1500
    override val defaultPrompt: String? get() = AnalyzerPrompts.DESCRIPTION_QUALITY
    override val responseFormatSuffix: String get() = RESPONSE_FORMAT_SUFFIX
    override val supportedEnrichment: List<String> = listOf("jira")
    override val explanation: String get() =
        "Evaluates MR description clarity, completeness, and usefulness using AI. " +
            "Encourages better documentation of changes."

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        val description = context.mr.description
        if (description.isNullOrBlank()) {
            return listOf(
                FindingFactory.warn(
                    rule = ANALYZER_ID,
                    message = "MR description is empty.",
                    suggestion = "Add a description explaining what changed and why.",
                    metadata = mapOf(
                        AiFindingMetadataKeys.IS_DESCRIPTION_QUALITY to "true",
                        AiFindingMetadataKeys.AI_TOKENS_USED to "0",
                        AiFindingMetadataKeys.QUALITY_SCORE to "0.0",
                    ),
                ),
            )
        }

        val aiConfig = AiRuleConfig.fromPluginConfig(context.config)
        val tokens = aiConfig.tokensFor(featureKey)
        val prompt = buildPrompt(context)
        // extra instructions (linked ticket text) go once, as data in the user prompt (buildPrompt).
        val systemPrompt = AiRuleAnalyzerUtils.effectivePrompt(
            AnalyzerPrompts.DESCRIPTION_QUALITY,
            systemPromptOverride = context.resolvedPrompt,
            responseFormatSuffix = responseFormatSuffix,
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

        return parseDescriptionResponse(response.content, response.totalTokens, response.model)
    }

    internal fun buildPrompt(context: AiAnalyzerContext): String =
        buildString {
            if (!context.extraInstructions.isNullOrBlank()) {
                appendLine("## Knowledge Context")
                appendLine(context.extraInstructions)
                appendLine()
            }
            appendLine("## Merge Request")
            append(context.diffContext.metadata)
            appendLine()
            appendLine("## Description")
            appendLine(context.mr.description ?: "(none)")
        }

    internal fun parseDescriptionResponse(
        content: String,
        tokensUsed: Int,
        aiModel: String,
    ): List<Finding> {
        val json = KarandashJson.Default
        val result = suspendCatching {
            json.decodeFromString<DescriptionResult>(AiRuleAnalyzerUtils.extractJson(content))
        }.getOrNull()
            ?: return AiRuleAnalyzerUtils.parseErrorFinding(
                ANALYZER_ID,
                content,
                aiModel,
                tokensUsed,
                "Description quality analysis",
            )

        if (result.issues.isEmpty()) return emptyList()

        val issueLines = result.issues.joinToString("; ") { issue ->
            "${issue.category}: ${issue.suggestion}"
        }

        return listOf(
            FindingFactory.warn(
                rule = ANALYZER_ID,
                message = "Description quality issues (score: ${result.qualityScore.formatDecimals(2)}): ${result.summary}",
                suggestion = issueLines,
                metadata = mapOf(
                    AiFindingMetadataKeys.IS_DESCRIPTION_QUALITY to "true",
                    AiFindingMetadataKeys.QUALITY_SCORE to result.qualityScore.toString(),
                    AiFindingMetadataKeys.ISSUE_COUNT to result.issues.size.toString(),
                ),
            ),
        )
    }

    companion object {
        /** Module ID used for registration and lookup. */
        const val ID = "ai-description-quality-analyzer"

        /** Analyzer rule ID used in findings and config. */
        const val ANALYZER_ID = "description-quality"

        private val RESPONSE_FORMAT_SUFFIX = """
            |{
            |  "qualityScore": 0.65,
            |  "issues": [
            |    {
            |      "category": "what_changed|why_changed|test_plan|issue_links|clarity",
            |      "suggestion": "actionable suggestion to improve the description"
            |    }
            |  ],
            |  "summary": "one-sentence summary of the description quality"
            |}
        """.trimMargin()
    }
}

@Serializable
internal data class DescriptionResult(
    val qualityScore: Double,
    val issues: List<DescriptionIssue> = emptyList(),
    val summary: String,
)

@Serializable
internal data class DescriptionIssue(
    val category: String,
    val suggestion: String,
)
