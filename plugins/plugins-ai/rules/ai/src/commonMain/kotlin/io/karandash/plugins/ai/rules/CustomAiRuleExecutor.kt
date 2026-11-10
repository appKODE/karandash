package io.karandash.plugins.ai.rules

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiService
import io.karandash.api.ai.AiTokenBudget
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.schema.JsonSchemaGenerator
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.security.TextRedactor
import kotlinx.serialization.Serializable

/**
 * Executes a config-defined [CustomAiRule] against a merge request.
 *
 * Sends the rule's system prompt, the MR context and the prepared (capped) diff to the AI service (or the provider
 * when no service is available), then parses the response as a JSON object
 * `{"message": "...", "explanation": "...", "suggestion": "...", "severity": "..."}`. A reply that is not that JSON
 * becomes the standard INFO parse-error finding, never a finding at the rule's severity.
 */
internal object CustomAiRuleExecutor {
    /** Token estimate the dispatcher's budget gate checks before a custom rule runs. */
    const val ESTIMATED_TOKENS = 1500

    private val json = KarandashJson.Default
    private val responseSchema = JsonSchemaGenerator.fromDescriptor(CustomRuleResult.serializer().descriptor)

    /**
     * Run a single custom rule against the provided merge request.
     *
     * @param rule The custom rule definition
     * @param mr The merge request to analyze
     * @param aiService The rule's AI service view: prompt processors, redactors, interceptors and
     *   response transformers run with this rule's id, and its usage is metered. Null falls back to [provider].
     * @param provider AI provider for completion calls when [aiService] is null
     * @param budget Token budget tracker
     * @param model AI model identifier
     * @param preparedDiff The rule's diff context, already capped by the dispatcher
     * @param language `ai.responseLanguage`: the language of the finding texts; null or English needs no instruction
     * @return List of findings, or empty if the response is blank
     */
    suspend fun execute(
        rule: CustomAiRule,
        mr: MergeRequest,
        aiService: AiService?,
        provider: AiProvider,
        budget: AiTokenBudget,
        model: String,
        preparedDiff: String = "",
        language: String? = null,
    ): List<Finding> {
        val prompt = buildPrompt(mr, preparedDiff)
        val systemPrompt = LanguagePromptOverlays.withOutputLanguage(rule.prompt, language)
        val (content, responseModel, totalTokens) = if (aiService != null) {
            val request = AiCompletionRequest(
                prompt = prompt,
                systemPrompt = systemPrompt,
                model = model,
                maxTokens = rule.maxTokens,
                temperature = rule.temperature,
                responseSchema = responseSchema,
            )
            aiService.complete(request).let { Triple(it.content, it.model, it.totalTokens) }
        } else {
            // AiService redacts the assembled prompt itself; the direct path must do it here.
            provider
                .complete(TextRedactor.redact(prompt), TextRedactor.redact(systemPrompt), model, rule.maxTokens, rule.temperature)
                .let { Triple(it.content, it.model, it.totalTokens) }
        }
        budget.recordSpend(totalTokens)

        return parseResponse(rule, content, responseModel, totalTokens)
    }

    private fun buildPrompt(
        mr: MergeRequest,
        preparedDiff: String,
    ): String =
        buildString {
            appendLine("## Merge Request: ${mr.title}")
            appendLine("Description: ${mr.description ?: "(no description)"}")
            appendLine("Branch: ${mr.sourceBranch} → ${mr.targetBranch}")
            appendLine("Labels: ${mr.labels.joinToString(", ").ifEmpty { "(none)" }}")
            appendLine()
            appendLine("## Changed Files (${mr.changes.size})")
            for (change in mr.changes) {
                appendLine("- ${change.path} (+${change.additions}/-${change.deletions})")
            }
            if (preparedDiff.isNotBlank()) {
                appendLine()
                appendLine("## Diff")
                append(preparedDiff)
            }
        }

    private fun parseResponse(
        rule: CustomAiRule,
        content: String,
        aiModel: String,
        tokensUsed: Int,
    ): List<Finding> {
        val defaultSeverity = Severity.fromString(rule.severity, default = Severity.WARNING)
        val meta = mapOf(AiFindingMetadataKeys.AI_MODEL to aiModel, AiFindingMetadataKeys.AI_TOKENS_USED to tokensUsed.toString())

        return try {
            val jsonStr = AiRuleAnalyzerUtils.extractJson(content)
            val obj = json.decodeFromString<CustomRuleResult>(jsonStr)
            if (obj.message.isBlank()) return emptyList()
            val sev = obj.severity?.let { Severity.fromString(it, default = defaultSeverity) } ?: defaultSeverity
            listOf(
                Finding(
                    severity = sev,
                    message = obj.message,
                    rule = rule.id,
                    explanation = obj.explanation,
                    suggestion = obj.suggestion?.takeIf { it.isNotBlank() },
                    metadata = meta,
                )
            )
        } catch (ignored: Exception) {
            // boundary: parsing untrusted LLM output
            ignored.rethrowCancellation()
            if (content.isBlank()) {
                emptyList()
            } else {
                AiRuleAnalyzerUtils.parseErrorFinding(rule.id, content, aiModel, tokensUsed, "Custom AI rule '${rule.id}'")
            }
        }
    }
}

@Serializable
private data class CustomRuleResult(
    val message: String = "",
    val explanation: String? = null,
    /** One imperative sentence on how to fix it; becomes [Finding.suggestion]. */
    val suggestion: String? = null,
    val severity: String? = null,
)
