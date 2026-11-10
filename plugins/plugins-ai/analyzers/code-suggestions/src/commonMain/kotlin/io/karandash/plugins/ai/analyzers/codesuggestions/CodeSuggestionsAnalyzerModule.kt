package io.karandash.plugins.ai.analyzers.codesuggestions

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.support.rethrowCancellation
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
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
import kotlin.math.abs

/**
 * AI-powered code suggestion generator. Analyzes merge request diffs and produces
 * concrete code improvement suggestions with existing/suggested code pairs.
 */
class CodeSuggestionsAnalyzerModule : AbstractAiAnalyzer(AiRuleIds.CODE_SUGGESTIONS, "codeSuggestions") {
    override val id: String = "ai-code-suggestions-analyzer"
    override val name: String = "AI Code Suggestions"
    override val resultSerializer: KSerializer<*> = CodeSuggestionsResult.serializer()
    override val defaultEnabled: Boolean = false
    override val estimatedTokens: Int = 3000
    override val defaultPrompt: String = AnalyzerPrompts.CODE_SUGGESTIONS
    override val responseFormatSuffix: String = RESPONSE_FORMAT_SUFFIX
    override val explanation: String =
        "Generates concrete code improvement suggestions with existing/suggested code pairs, similar to inline review suggestions."
    override val needsScopeDetection: Boolean = true
    override val supportsSemiFormalReasoning: Boolean = true

    private val json = KarandashJson.Default

    companion object {
        private val RESPONSE_FORMAT_SUFFIX = """
            |{
            |  "suggestions": [
            |    {
            |      "file": "src/example/Foo.kt",
            |      "line": 42,
            |      "existingCode": "val x = list.filter { it != null }.first()",
            |      "suggestedCode": "val x = list.firstOrNull { it != null } ?: error(\"not found\")",
            |      "explanation": "why this change improves the code",
            |      "category": "correctness|security|performance|readability"
            |    }
            |  ]
            |}
        """.trimMargin()
    }

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
            )
        )

        return parseResponse(
            content = response.content,
            aiModel = response.model,
            tokensUsed = response.totalTokens,
            maxSuggestions = aiConfig.maxComments,
            mr = context.mr,
            logger = context.logger,
        )
    }

    internal fun buildPrompt(context: AiAnalyzerContext,): String {
        val mr = context.mr
        val aiConfig = AiRuleConfig.fromPluginConfig(context.config)
        return buildString {
            append(PromptSections.mergeRequest(mr))

            appendLine("## Instructions")
            appendLine("Generate up to ${aiConfig.maxComments} code improvement suggestions.")
            appendLine()

            appendLine("## File Diffs")
            append(context.diffContext.preparedDiff)
        }
    }

    internal fun parseResponse(
        content: String,
        aiModel: String,
        tokensUsed: Int,
        maxSuggestions: Int,
        mr: MergeRequest? = null,
        logger: Logger = NoOpLogger,
    ): List<Finding> =
        try {
            val jsonStr = AiRuleAnalyzerUtils.extractJson(content)
            val result = json.decodeFromString<CodeSuggestionsResult>(jsonStr)

            val addedLinesByFile = mr?.changes?.associate { it.path to AiRuleAnalyzerUtils.extractAddedLines(it.diffContent) }

            val knownPaths = mr?.changes?.map { it.path }?.toSet()
            // Validate first, cap afterwards: invalid suggestions must not use up the maxSuggestions budget.
            result.suggestions
                .mapNotNull { raw ->
                    val suggestion = raw.copy(file = AiRuleAnalyzerUtils.normalizeDiffPath(raw.file, knownPaths))
                    if (suggestion.line <= 0) {
                        logger.debug(
                            "[ai-line-diagnostics] drop [$analyzerId] file=${suggestion.file} " +
                                "llm_line=${suggestion.line} reason=non_positive model=$aiModel",
                        )
                        return@mapNotNull null
                    }

                    val added = addedLinesByFile?.get(suggestion.file)
                    val hasAddedLines = !added.isNullOrEmpty()
                    val effectiveLine = if (hasAddedLines) {
                        val snapped = AiRuleAnalyzerUtils.snapToAddedLine(suggestion.line, checkNotNull(added))
                        if (snapped == null) {
                            logger.debug(
                                "[ai-line-diagnostics] drop [$analyzerId] file=${suggestion.file} " +
                                    "llm_line=${suggestion.line} reason=snap_failed model=$aiModel",
                            )
                            return@mapNotNull null
                        }
                        snapped
                    } else {
                        suggestion.line
                    }

                    logger.debug(
                        "[ai-line-diagnostics] keep [$analyzerId] file=${suggestion.file} " +
                            "llm_line=${suggestion.line} snapped_line=$effectiveLine " +
                            "snap_delta=${abs(effectiveLine - suggestion.line)} " +
                            "added_lines_known=$hasAddedLines model=$aiModel",
                    )

                    val severity = when (suggestion.category.lowercase()) {
                        "correctness", "security" -> Severity.WARNING
                        else -> Severity.INFO
                    }

                    Finding(
                        severity = severity,
                        message = suggestion.explanation,
                        rule = analyzerId,
                        location = Location.FileRef(path = suggestion.file, line = effectiveLine),
                        metadata = mapOf(
                            AiFindingMetadataKeys.EXISTING_CODE to suggestion.existingCode,
                            AiFindingMetadataKeys.SUGGESTED_CODE to suggestion.suggestedCode,
                            AiFindingMetadataKeys.CATEGORY to suggestion.category,
                        ),
                    )
                }.take(maxSuggestions)
        } catch (ignored: Exception) {
            // boundary: parsing untrusted LLM output
            ignored.rethrowCancellation()
            AiRuleAnalyzerUtils.parseErrorFinding(
                ruleId = analyzerId,
                content = content,
                aiModel = aiModel,
                tokensUsed = tokensUsed,
                prefix = "AI code suggestion generation",
            )
        }
}

@Serializable
internal data class CodeSuggestionsResult(
    val suggestions: List<CodeSuggestion> = emptyList(),
)

@Serializable
internal data class CodeSuggestion(
    val file: String,
    val line: Int,
    val existingCode: String,
    val suggestedCode: String,
    val explanation: String,
    val category: String = "readability",
)
