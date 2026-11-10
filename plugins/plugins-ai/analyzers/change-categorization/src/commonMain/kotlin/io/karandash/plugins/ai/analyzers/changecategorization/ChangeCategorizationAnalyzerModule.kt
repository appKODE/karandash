package io.karandash.plugins.ai.analyzers.changecategorization

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.support.suspendCatching
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AbstractAiAnalyzer
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.ProcessRiskMetrics
import io.karandash.plugins.ai.core.TriageRisk
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.tokensFor
import io.karandash.plugins.ai.prompts.AnalyzerPrompts
import io.karandash.utils.finding.FindingFactory
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * AI-powered change categorization analyzer.
 *
 * Automatically categorizes merge request changes (feature, bugfix, refactor, etc.)
 * and suggests appropriate labels using AI analysis of file changes and commit messages.
 */
class ChangeCategorizationAnalyzerModule : AbstractAiAnalyzer(ANALYZER_ID, "changeCategorization") {
    override val id: String get() = ID
    override val name: String get() = "AI Change Categorization"
    override val mrLevel: Boolean get() = true
    override val resultSerializer: KSerializer<*> = CategorizationResult.serializer()
    override val defaultEnabled: Boolean get() = false
    override val estimatedTokens: Int get() = 1500
    override val defaultPrompt: String? get() = AnalyzerPrompts.CHANGE_CATEGORIZATION
    override val responseFormatSuffix: String get() = RESPONSE_FORMAT_SUFFIX
    override val explanation: String get() =
        "Automatically categorizes changes (feature, bugfix, refactor, etc.) and suggests appropriate labels " +
            "using AI analysis."

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        val aiConfig = AiRuleConfig.fromPluginConfig(context.config)
        val tokens = aiConfig.tokensFor(featureKey)
        val risk = TriageRisk.measure(context)
        val prompt = buildPrompt(context, risk)
        val systemPrompt = AiRuleAnalyzerUtils.effectivePrompt(
            AnalyzerPrompts.CHANGE_CATEGORIZATION,
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

        return TriageRisk.stamp(parseCategorizationResponse(response.content, response.totalTokens, response.model), risk)
    }

    private fun buildPrompt(
        context: AiAnalyzerContext,
        risk: ProcessRiskMetrics,
    ): String {
        val mr = context.mr
        return buildString {
            append(TriageRisk.promptSection(risk))
            appendLine()
            appendLine("## Merge Request")
            append(context.diffContext.metadata)
            appendLine("Current labels: ${mr.labels.joinToString(", ").ifEmpty { "(none)" }}")
            appendLine()
            appendLine("## Commit Messages")
            mr.commits.forEach { commit ->
                appendLine("- ${commit.message.lines().firstOrNull() ?: commit.message}")
            }
        }
    }

    private fun parseCategorizationResponse(
        content: String,
        tokensUsed: Int,
        aiModel: String,
    ): List<Finding> {
        val json = KarandashJson.Default
        val result = suspendCatching {
            json.decodeFromString<CategorizationResult>(AiRuleAnalyzerUtils.extractJson(content))
        }.getOrNull()
            ?: return AiRuleAnalyzerUtils.parseErrorFinding(
                ANALYZER_ID,
                content,
                aiModel,
                tokensUsed,
                "Change categorization",
            )

        if (result.suggestedLabels.isEmpty()) return emptyList()

        return listOf(
            FindingFactory.info(
                rule = ANALYZER_ID,
                message = "Suggested labels: ${result.suggestedLabels.joinToString(", ")}",
                explanation = result.reasoning,
                metadata = mapOf(
                    AiFindingMetadataKeys.IS_CATEGORIZATION to "true",
                    AiFindingMetadataKeys.SUGGESTED_LABELS to result.suggestedLabels.joinToString(", "),
                    AiFindingMetadataKeys.CONFIDENCE to result.confidence.toString(),
                ),
            ),
        )
    }

    companion object {
        /** Module ID used for registration and lookup. */
        const val ID = "ai-change-categorization-analyzer"

        /** Analyzer rule ID used in findings and config. */
        const val ANALYZER_ID = "change-categorization"

        private val RESPONSE_FORMAT_SUFFIX = """
            |{
            |  "suggestedLabels": ["label1", "label2"],
            |  "confidence": 0.85,
            |  "reasoning": "brief explanation of why these labels apply"
            |}
        """.trimMargin()
    }
}

@Serializable
internal data class CategorizationResult(
    val suggestedLabels: List<String> = emptyList(),
    val confidence: Double,
    val reasoning: String,
)
