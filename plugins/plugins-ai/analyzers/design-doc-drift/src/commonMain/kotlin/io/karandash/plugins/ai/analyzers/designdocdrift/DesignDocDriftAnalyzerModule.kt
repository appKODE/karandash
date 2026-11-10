package io.karandash.plugins.ai.analyzers.designdocdrift

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.finding.Finding
import io.karandash.models.finding.PresentationKeys
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AbstractAiAnalyzer
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.PromptSections
import io.karandash.plugins.ai.core.context.DesignDocEnricher
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.tokensFor
import io.karandash.plugins.ai.prompts.AnalyzerPrompts
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * AI-powered design-doc drift analyzer.
 *
 * Compares MR diff against design documentation (Confluence pages) injected via
 * the Knowledge Context enrichment pipeline. Flags implementation that diverges
 * from the documented design.
 *
 * Opt-in: [defaultEnabled] is `false`. Requires Confluence enrichment to be
 * configured; skips silently when no design doc content is available.
 */
class DesignDocDriftAnalyzerModule : AbstractAiAnalyzer(ANALYZER_ID, "designDocDrift") {
    override val id: String get() = ID
    override val name: String get() = "AI Design Doc Drift"
    override val resultSerializer: KSerializer<*> = DriftResult.serializer()
    override val defaultEnabled: Boolean get() = false
    override val estimatedTokens: Int get() = 3000
    override val supportedEnrichment: List<String> = listOf("jira", "confluence")
    override val defaultPrompt: String get() = AnalyzerPrompts.DESIGN_DOC_DRIFT
    override val responseFormatSuffix: String get() = RESPONSE_FORMAT_SUFFIX
    override val explanation: String get() =
        "Compares MR implementation against design documentation (Confluence pages) " +
            "and flags divergence from the documented design."

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        // The design doc arrives as a shared context block, ahead of this analyzer's prompt.
        if (context.contextBlocks.none { it.source == DesignDocEnricher.MODULE_ID }) {
            return context.skip("no design doc content in enrichment context")
        }

        val aiConfig = AiRuleConfig.fromPluginConfig(context.config)
        val tokens = aiConfig.tokensFor(featureKey)
        val systemPrompt = AiRuleAnalyzerUtils.effectivePrompt(
            AnalyzerPrompts.DESIGN_DOC_DRIFT,
            context.extraInstructions,
            context.resolvedPrompt,
            responseFormatSuffix,
        )
        val prompt = buildPrompt(context)

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

        return parseResponse(response.content, response.model, response.totalTokens)
    }

    internal fun buildPrompt(context: AiAnalyzerContext): String =
        buildString {
            append(PromptSections.mergeRequest(context.mr))

            appendLine("## Changed Files (${context.mr.changes.size} total)")
            for (change in context.mr.changes) {
                appendLine("- ${change.path} [${change.changeType}] (+${change.additions}/-${change.deletions})")
            }
            appendLine()

            appendLine("## Diff")
            append(context.diffContext.preparedDiff)
        }

    internal fun parseResponse(
        content: String,
        aiModel: String,
        tokensUsed: Int,
    ): List<Finding> =
        try {
            val jsonStr = AiRuleAnalyzerUtils.extractJson(content)
            val result = KarandashJson.Default.decodeFromString<DriftResult>(jsonStr)

            if (!result.hasDrift) return emptyList()

            val severity = if (result.driftScore >= HIGH_DRIFT_THRESHOLD) Severity.WARNING else Severity.INFO

            val metadata = buildMap {
                put(AiFindingMetadataKeys.IS_DESIGN_DOC_DRIFT, "true")
                put(PresentationKeys.SECTION, DRIFT_SECTION)
                put(AiFindingMetadataKeys.DRIFT_SCORE, result.driftScore.toString())
                if (result.divergedSections.isNotEmpty()) {
                    put(AiFindingMetadataKeys.DIVERGED_SECTIONS, result.divergedSections.joinToString("; "))
                }
            }

            val explanation = result.divergedSections
                .takeIf { it.isNotEmpty() }
                ?.let { "Diverged sections: ${it.joinToString(", ")}" }

            listOf(
                Finding(
                    severity = severity,
                    message = "Design doc drift detected (score: ${formatScore(result.driftScore)}): ${result.summary}",
                    rule = analyzerId,
                    explanation = explanation,
                    suggestion = result.recommendations.joinToString("; ").ifEmpty { null },
                    metadata = metadata,
                ),
            )
        } catch (ignored: Exception) {
            // boundary: parsing untrusted LLM output
            ignored.rethrowCancellation()
            AiRuleAnalyzerUtils.parseErrorFinding(
                ruleId = analyzerId,
                content = content,
                aiModel = aiModel,
                tokensUsed = tokensUsed,
                prefix = "AI design-doc drift analysis",
            )
        }

    companion object {
        const val ID = "ai-design-doc-drift-analyzer"
        const val ANALYZER_ID = "design-doc-drift"

        private const val HIGH_DRIFT_THRESHOLD = 0.6

        private const val DRIFT_SECTION = "Design doc drift"

        private fun formatScore(value: Double): String {
            val pct = (value * 100).roundToInt()
            return "$pct%"
        }

        private val RESPONSE_FORMAT_SUFFIX = """
            |{
            |  "hasDrift": true,
            |  "driftScore": 0.75,
            |  "divergedSections": ["section that diverged"],
            |  "recommendations": ["actionable recommendation"],
            |  "summary": "one-sentence drift assessment"
            |}
        """.trimMargin()
    }
}

@Serializable
internal data class DriftResult(
    val hasDrift: Boolean = false,
    val driftScore: Double = 0.0,
    val divergedSections: List<String> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val summary: String = "",
)
