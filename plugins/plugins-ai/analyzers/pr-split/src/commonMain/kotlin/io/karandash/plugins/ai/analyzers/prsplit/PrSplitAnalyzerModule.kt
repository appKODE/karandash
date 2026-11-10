package io.karandash.plugins.ai.analyzers.prsplit

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AbstractAiAnalyzer
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.ProcessRiskMetrics
import io.karandash.plugins.ai.core.TriageRisk
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.tokensFor
import io.karandash.plugins.ai.prompts.AnalyzerPrompts
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * AI-powered PR split advisor. Analyzes large MRs and suggests whether they should be
 * split into smaller, independent merge requests for easier review.
 *
 * Only triggers when the number of changed files exceeds [AiRuleConfig.splitThreshold].
 */
class PrSplitAnalyzerModule : AbstractAiAnalyzer(ANALYZER_ID, "prSplit") {
    override val id = "ai-pr-split-analyzer"
    override val name = "AI PR Split"
    override val mrLevel: Boolean get() = true
    override val resultSerializer: KSerializer<*> = PrSplitResult.serializer()
    override val defaultEnabled: Boolean = false
    override val estimatedTokens = 2000
    override val defaultPrompt: String = AnalyzerPrompts.PR_SPLIT
    override val responseFormatSuffix: String = RESPONSE_FORMAT_SUFFIX
    override val explanation =
        "Analyzes large MRs and suggests whether they should be split into smaller, independent merge requests for easier review."

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        val aiConfig = AiRuleConfig.fromPluginConfig(context.config)
        val tokens = aiConfig.tokensFor(featureKey)

        if (context.mr.changes.size <= aiConfig.splitThreshold) {
            return context.skip("threshold not met (${context.mr.changes.size} files <= ${aiConfig.splitThreshold})")
        }
        val systemPrompt = AiRuleAnalyzerUtils.effectivePrompt(
            defaultPrompt,
            context.extraInstructions,
            context.resolvedPrompt,
            responseFormatSuffix,
        )
        val risk = TriageRisk.measure(context)
        val prompt = buildPrompt(context, risk)

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

        return TriageRisk.stamp(parseResponse(response.content, response.model, response.totalTokens), risk)
    }

    internal fun buildPrompt(
        context: AiAnalyzerContext,
        risk: ProcessRiskMetrics = TriageRisk.of(context),
    ): String =
        buildString {
            append(TriageRisk.promptSection(risk))
            appendLine()
            appendLine("## Merge Request")
            append(context.diffContext.metadata)
            appendLine()
            appendLine("## Commits (${context.mr.commits.size} total)")
            for (commit in context.mr.commits) {
                appendLine("- ${commit.message.lines().first()}")
            }
        }

    internal fun parseResponse(
        content: String,
        aiModel: String,
        tokensUsed: Int,
    ): List<Finding> {
        return try {
            val jsonStr = AiRuleAnalyzerUtils.extractJson(content)
            val result = json.decodeFromString<PrSplitResult>(jsonStr)

            // no split needed is not a finding.
            if (!result.shouldSplit) return emptyList()

            val explanation = buildString {
                appendLine(result.summary)
                appendLine()
                appendLine("Suggested splits:")
                result.suggestedSplits.forEachIndexed { index, split ->
                    appendLine("${index + 1}. **${split.name}**: ${split.rationale}")
                    appendLine("   Files: ${split.files.joinToString(", ")}")
                }
            }.trim()

            listOf(
                Finding(
                    severity = Severity.INFO,
                    message = "Consider splitting this MR into ${result.suggestedSplits.size} smaller PRs. " +
                        result.summary,
                    rule = analyzerId,
                    explanation = explanation,
                    metadata = mapOf(
                        AiFindingMetadataKeys.IS_PR_SPLIT to "true",
                        AiFindingMetadataKeys.SHOULD_SPLIT to "true",
                        AiFindingMetadataKeys.CONFIDENCE to result.confidence.toString(),
                        AiFindingMetadataKeys.SPLIT_COUNT to result.suggestedSplits.size.toString(),
                    ),
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
                prefix = "AI PR split analysis",
            )
        }
    }

    companion object {
        const val ANALYZER_ID = "pr-split"

        private val json = KarandashJson.Default

        private val RESPONSE_FORMAT_SUFFIX = """
            |{
            |  "shouldSplit": true,
            |  "confidence": 0.8,
            |  "suggestedSplits": [
            |    {
            |      "name": "descriptive name for this sub-PR",
            |      "rationale": "why these changes belong together",
            |      "files": ["file1.kt", "file2.kt"]
            |    }
            |  ],
            |  "summary": "one-sentence recommendation"
            |}
        """.trimMargin()
    }
}

@Serializable
internal data class PrSplitResult(
    val shouldSplit: Boolean = false,
    val confidence: Double = 0.0,
    val suggestedSplits: List<SuggestedSplit> = emptyList(),
    val summary: String = "",
)

@Serializable
internal data class SuggestedSplit(
    val name: String,
    val rationale: String,
    val files: List<String> = emptyList(),
)
