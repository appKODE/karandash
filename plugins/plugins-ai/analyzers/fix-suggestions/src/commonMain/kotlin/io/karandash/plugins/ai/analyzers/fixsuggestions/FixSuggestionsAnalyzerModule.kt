package io.karandash.plugins.ai.analyzers.fixsuggestions

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.plugins.ai.core.AbstractAiAnalyzer
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.PromptSections
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.tokensFor
import io.karandash.plugins.ai.prompts.AnalyzerPrompts
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * AI-powered fix suggester. Analyzes existing validation failures and suggests
 * actionable fixes to resolve ERROR and WARNING findings.
 */
class FixSuggestionsAnalyzerModule : AbstractAiAnalyzer(AiRuleIds.FIX_SUGGESTIONS, "fixSuggestions") {
    override val id: String = "ai-fix-suggestions-analyzer"
    override val name: String = "AI Fix Suggestions"
    override val resultSerializer: KSerializer<*> = FixSuggestionsResult.serializer()
    override val defaultEnabled: Boolean = false
    override val estimatedTokens: Int = 2500
    override val defaultPrompt: String = AnalyzerPrompts.FIX_SUGGESTIONS
    override val responseFormatSuffix: String = RESPONSE_FORMAT_SUFFIX
    override val explanation: String =
        "Provides AI-generated fix suggestions for detected issues, accelerating problem resolution."

    private val json = KarandashJson.Default

    companion object {
        private val RESPONSE_FORMAT_SUFFIX = """
            |{
            |  "fixes": [
            |    {
            |      "findingRule": "rule-id",
            |      "findingMessage": "original validation message",
            |      "suggestedFix": "specific, actionable fix description",
            |      "confidence": 0.9,
            |      "file": "path/of/the/file/to/change (only when the fix is a code edit)",
            |      "line": 42,
            |      "endLine": 43,
            |      "existingCode": "the exact lines line..endLine replace",
            |      "suggestedCode": "the replacement lines"
            |    }
            |  ]
            |}
        """.trimMargin()
    }

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        val actionableFindings = context.accumulatedFindings.filter {
            it.severity == Severity.ERROR || it.severity == Severity.WARNING
        }
        if (actionableFindings.isEmpty()) {
            return context.skip("no input data (no ERROR or WARNING findings to fix)")
        }

        val aiConfig = AiRuleConfig.fromPluginConfig(context.config)
        val tokens = aiConfig.tokensFor(featureKey)

        val prompt = buildPrompt(context, actionableFindings)
        val systemPrompt = AiRuleAnalyzerUtils.effectivePrompt(
            AnalyzerPrompts.FIX_SUGGESTIONS,
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
            )
        )

        return parseResponse(response.content, response.model, response.totalTokens)
    }

    internal fun buildPrompt(
        context: AiAnalyzerContext,
        existingFindings: List<Finding>,
    ): String {
        val mr = context.mr
        val sb = StringBuilder()

        sb.append(PromptSections.mergeRequest(mr, "Draft" to mr.draft.toString()))

        sb.appendLine("## Validation Failures")
        existingFindings.forEachIndexed { index, finding ->
            val ruleDisplay = ConfigKeyNormalizer.camelToKebabCase(finding.rule)
            sb.appendLine("${index + 1}. [$ruleDisplay] ${finding.severity}: ${finding.message}")
        }
        sb.appendLine()

        sb.appendLine("## Changed Files (${mr.changes.size} total)")
        for (change in mr.changes) {
            sb.appendLine("- ${change.path} [${change.changeType}] (+${change.additions}/-${change.deletions})")
        }
        sb.appendLine()

        sb.appendLine("## File Changes")
        sb.append(context.diffContext.preparedDiff)

        return sb.toString()
    }

    internal fun parseResponse(
        content: String,
        aiModel: String,
        tokensUsed: Int,
    ): List<Finding> {
        return try {
            val jsonStr = AiRuleAnalyzerUtils.extractJson(content)
            val result = json.decodeFromString<FixSuggestionsResult>(jsonStr)

            if (result.fixes.isEmpty()) {
                return emptyList()
            }

            result.fixes.map { fix ->
                Finding(
                    severity = Severity.INFO,
                    message = "[${fix.findingRule}] ${fix.findingMessage}",
                    rule = analyzerId,
                    suggestion = fix.suggestedFix,
                    location = fix.file?.takeIf { it.isNotBlank() }?.let { Location.FileRef(it, fix.line, fix.endLine) },
                    metadata = fix.codeChangeMetadata() + mapOf(
                        AiFindingMetadataKeys.IS_FIX_SUGGESTION to "true",
                        AiFindingMetadataKeys.SUGGESTED_FIX to fix.suggestedFix,
                        AiFindingMetadataKeys.ORIGINAL_RULE to fix.findingRule,
                        AiFindingMetadataKeys.ORIGINAL_MESSAGE to fix.findingMessage,
                        AiFindingMetadataKeys.CONFIDENCE to fix.confidence.toString(),
                    ),
                )
            }
        } catch (ignored: Exception) {
            // boundary: parsing untrusted LLM output
            ignored.rethrowCancellation()
            AiRuleAnalyzerUtils.parseErrorFinding(
                ruleId = analyzerId,
                content = content,
                aiModel = aiModel,
                tokensUsed = tokensUsed,
                prefix = "AI fix suggestions",
            )
        }
    }
}

@Serializable
internal data class FixSuggestionsResult(
    val fixes: List<FixSuggestion>,
)

@Serializable
internal data class FixSuggestion(
    val findingRule: String,
    val findingMessage: String,
    val suggestedFix: String,
    val confidence: Double,
    val file: String? = null,
    val line: Int? = null,
    val endLine: Int? = null,
    val existingCode: String? = null,
    val suggestedCode: String? = null,
) {
    /** The code-change keys `Finding.codeChange()` reads, set only when the fix carries replacement code. */
    fun codeChangeMetadata(): Map<String, String> =
        if (suggestedCode.isNullOrBlank()) {
            emptyMap()
        } else {
            listOfNotNull(
                AiFindingMetadataKeys.SUGGESTED_CODE to suggestedCode,
                existingCode?.let { AiFindingMetadataKeys.EXISTING_CODE to it },
            ).toMap()
        }
}
