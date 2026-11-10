package io.karandash.plugins.ai.analyzers.riskscoring

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.support.suspendCatching
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AbstractAiAnalyzer
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.ProcessRiskMetrics
import io.karandash.plugins.ai.core.TriageRisk
import io.karandash.plugins.ai.core.formatDecimals
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.tokensFor
import io.karandash.plugins.ai.prompts.AnalyzerPrompts
import io.karandash.utils.finding.FindingFactory
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * AI-powered risk scoring analyzer.
 *
 * Assesses the risk level of a merge request by analyzing metadata,
 * change patterns, and optionally diff content using an AI completion call.
 *
 * By default only metadata (title, description, file list, change counts) is sent
 * to keep prompts small and latency low. Set `riskScoringIncludeDiff: true` in the
 * AI plugin config to include truncated diff content for richer assessment.
 */
class RiskScoringAnalyzerModule : AbstractAiAnalyzer(ANALYZER_ID, "riskScoring") {
    override val id: String get() = ID
    override val name: String get() = "AI Risk Scoring"
    override val mrLevel: Boolean get() = true
    override val resultSerializer: KSerializer<*> = RiskResult.serializer()
    override val defaultEnabled: Boolean get() = true
    override val estimatedTokens: Int get() = ESTIMATED_TOKENS_METADATA_ONLY
    override val needsScopeDetection: Boolean get() = false
    override val defaultPrompt: String? get() = AnalyzerPrompts.RISK_SCORING
    override val responseFormatSuffix: String get() = RESPONSE_FORMAT_SUFFIX
    override val explanation: String get() =
        "AI-powered risk assessment analyzing code changes for potential bugs, security issues, and complexity. " +
            "Helps prioritize review effort."
    override val supportsSemiFormalReasoning: Boolean get() = true

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        val aiConfig = AiRuleConfig.fromPluginConfig(context.config)
        val tokens = aiConfig.tokensFor(featureKey)
        val risk = TriageRisk.measure(context)
        val prompt = buildPrompt(context, aiConfig, risk)
        val systemPrompt = AiRuleAnalyzerUtils.effectivePrompt(
            AnalyzerPrompts.RISK_SCORING,
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

        val findings = TriageRisk.stamp(parseRiskResponse(response.content, response.totalTokens, response.model, aiConfig), risk)
        if (findings.isEmpty()) {
            context.logger.debug("[$ANALYZER_ID] No finding: risk score below warning threshold")
        }
        return findings
    }

    private fun buildPrompt(
        context: AiAnalyzerContext,
        aiConfig: AiRuleConfig,
        risk: ProcessRiskMetrics,
    ): String =
        buildString {
            append(TriageRisk.promptSection(risk))
            appendLine()
            appendLine("## Merge Request Metadata")
            append(context.diffContext.metadata)
            appendLine()
            appendLine("## Changed Files")
            context.mr.changes.forEach { change ->
                appendLine("- ${change.path} (+${change.additions}/-${change.deletions})")
            }
            if (aiConfig.riskScoringIncludeDiff) {
                val diffSection = context.diffContext.preparedDiff.let { diff ->
                    aiConfig.riskScoring.maxDiffChars?.let(diff::take) ?: diff
                }
                if (diffSection.isNotBlank()) {
                    appendLine()
                    appendLine("## Diff Content")
                    append(diffSection)
                }
            }
        }

    private fun parseRiskResponse(
        content: String,
        tokensUsed: Int,
        aiModel: String,
        aiConfig: AiRuleConfig,
    ): List<Finding> {
        val json = KarandashJson.Default
        val result = suspendCatching {
            json.decodeFromString<RiskResult>(AiRuleAnalyzerUtils.extractJson(content))
        }.getOrNull()
            ?: return AiRuleAnalyzerUtils.parseErrorFinding(ANALYZER_ID, content, aiModel, tokensUsed, "Risk scoring")

        val metadata = mapOf(
            AiFindingMetadataKeys.IS_RISK_ADVISORY to "true",
            AiFindingMetadataKeys.RISK_SCORE to result.riskScore.toString(),
            AiFindingMetadataKeys.RISK_LEVEL to result.riskLevel,
            AiFindingMetadataKeys.CONFIDENCE to result.confidence.toString(),
            AiFindingMetadataKeys.RISK_FACTORS to result.factors.joinToString(", "),
        )

        val highRisk = result.riskScore >= aiConfig.riskErrorThreshold
        val level = if (highRisk) "High" else "Medium"
        val message = "$level risk MR (score: ${result.riskScore.formatDecimals(2)}): ${result.summary}"
        return when {
            // ERROR only when the project set thresholds.riskError
            highRisk && aiConfig.riskErrorEnabled -> {
                listOf(FindingFactory.error(rule = ANALYZER_ID, message = message, metadata = metadata))
            }

            // Otherwise the score is an INFO advisory for the summary's risk line, never a WARNING of its own
            result.riskScore >= aiConfig.riskWarningThreshold -> {
                listOf(FindingFactory.info(rule = ANALYZER_ID, message = message, metadata = metadata))
            }

            else -> {
                emptyList()
            }
        }
    }

    companion object {
        /** Module ID used for registration and lookup. */
        const val ID = "ai-risk-scoring-analyzer"

        /** Analyzer rule ID used in findings and config. */
        const val ANALYZER_ID = AiRuleIds.RISK_SCORING

        private const val ESTIMATED_TOKENS_METADATA_ONLY = 500

        private val RESPONSE_FORMAT_SUFFIX = """
            |{
            |  "riskScore": 0.75,
            |  "riskLevel": "low|medium|high",
            |  "confidence": 0.85,
            |  "factors": ["risk factor 1", "risk factor 2"],
            |  "summary": "one-sentence risk summary"
            |}
        """.trimMargin()
    }
}

@Serializable
internal data class RiskResult(
    val riskScore: Double,
    val riskLevel: String,
    val confidence: Double,
    val factors: List<String> = emptyList(),
    val summary: String,
)
