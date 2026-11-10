package io.karandash.plugins.ai.analyzers.generatedescription

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AbstractAiAnalyzer
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.PromptSections
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.tokensFor
import io.karandash.plugins.ai.prompts.AnalyzerPrompts
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * AI-powered MR description generator. Suggests an improved title and description
 * when the current description is poor or missing. The description is proposed as a
 * [FixSuggestion.SetDescription], so `--fix` writes it to the MR.
 */
class GenerateDescriptionAnalyzerModule : AbstractAiAnalyzer(ANALYZER_ID, "generateDescription") {
    override val id = "ai-generate-description-analyzer"
    override val name = "AI Generate Description"
    override val mrLevel: Boolean get() = true
    override val resultSerializer: KSerializer<*> = GeneratedDescription.serializer()
    override val defaultEnabled: Boolean = false
    override val estimatedTokens = 2500
    override val defaultPrompt: String = AnalyzerPrompts.GENERATE_DESCRIPTION
    override val responseFormatSuffix: String = RESPONSE_FORMAT_SUFFIX
    override val explanation =
        "Generates an improved MR title and description when the current description is poor or missing."

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        val aiConfig = AiRuleConfig.fromPluginConfig(context.config)
        val tokens = aiConfig.tokensFor(featureKey)
        val systemPrompt = AiRuleAnalyzerUtils.effectivePrompt(
            defaultPrompt,
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

    internal fun buildPrompt(context: AiAnalyzerContext,): String {
        val mr = context.mr
        return buildString {
            append(PromptSections.mergeRequest(mr))

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

            appendLine("## Diff Previews")
            append(context.diffContext.preparedDiff)
        }
    }

    internal fun parseResponse(
        content: String,
        aiModel: String,
        tokensUsed: Int,
    ): List<Finding> =
        try {
            val jsonStr = AiRuleAnalyzerUtils.extractJson(content)
            val result = json.decodeFromString<GeneratedDescription>(jsonStr)

            val explanation = buildString {
                appendLine("**Suggested title:** ${result.suggestedTitle}")
                appendLine()
                appendLine("**Suggested description:**")
                appendLine(result.suggestedDescription)
                if (result.changesSummary.isNotEmpty()) {
                    appendLine()
                    appendLine("**Changes summary:**")
                    result.changesSummary.forEach { appendLine("- $it") }
                }
            }.trim()

            listOf(
                Finding(
                    severity = Severity.INFO,
                    message = "AI-generated description available. Suggested title: \"${result.suggestedTitle}\"",
                    rule = analyzerId,
                    explanation = explanation,
                    fixSuggestions = listOf(FixSuggestion.SetDescription(result.suggestedDescription)),
                    metadata = mapOf(
                        AiFindingMetadataKeys.IS_GENERATED_DESCRIPTION to "true",
                        AiFindingMetadataKeys.SUGGESTED_TITLE to result.suggestedTitle,
                        AiFindingMetadataKeys.SUGGESTED_DESCRIPTION to result.suggestedDescription,
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
                prefix = "AI description generation",
            )
        }

    companion object {
        const val ANALYZER_ID = "generate-description"

        private val json = KarandashJson.Default

        private val RESPONSE_FORMAT_SUFFIX = """
            |{
            |  "suggestedTitle": "clear, descriptive title",
            |  "suggestedDescription": "full markdown description",
            |  "changesSummary": ["bullet 1: change description", "bullet 2"]
            |}
        """.trimMargin()
    }
}

@Serializable
internal data class GeneratedDescription(
    val suggestedTitle: String,
    val suggestedDescription: String,
    val changesSummary: List<String> = emptyList(),
)
