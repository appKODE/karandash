@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.analyzers.similarissues

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunServiceKeys
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AbstractAiAnalyzer
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.PromptSections
import io.karandash.plugins.ai.core.SecretRedactor
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.tokensFor
import io.karandash.plugins.ai.prompts.AnalyzerPrompts
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * AI-powered similar issue detector. Searches the platform for past merge requests by the diff's keywords
 * ([SimilarIssuesHistory]) and asks the model which of them relate to this one. Without a platform that can
 * search, the rule is skipped.
 */
class SimilarIssuesAnalyzerModule : AbstractAiAnalyzer("similar-issues", "similarIssues") {
    override val id: String = "ai-similar-issues-analyzer"
    override val name: String = "AI Similar Issues"
    override val resultSerializer: KSerializer<*> = SimilarIssuesResult.serializer()
    override val defaultEnabled: Boolean = false
    override val estimatedTokens: Int = 2000
    override val defaultPrompt: String = AnalyzerPrompts.SIMILAR_ISSUES
    override val responseFormatSuffix: String = RESPONSE_FORMAT_SUFFIX
    override val explanation: String =
        "Detects similar past issues or patterns using AI, helping identify recurring problems and learning from history."

    private val json = KarandashJson.Default

    companion object {
        private val RESPONSE_FORMAT_SUFFIX = """
            |{
            |  "patterns": [
            |    {"description": "description of the pattern", "confidence": 0.85}
            |  ],
            |  "searchTerms": ["keyword1", "keyword2"]
            |}
        """.trimMargin()
    }

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        val history = loadHistory(context) ?: return context.skip("the platform cannot search past merge requests")
        if (history.isEmpty()) return context.skip("no past merge request matches the diff keywords")
        val aiConfig = AiRuleConfig.fromPluginConfig(context.config)
        val tokens = aiConfig.tokensFor(featureKey)

        val prompt = buildPrompt(context.mr, history)
        val systemPrompt = AiRuleAnalyzerUtils.effectivePrompt(
            AnalyzerPrompts.SIMILAR_ISSUES,
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

        return parseResponse(
            response.content,
            response.model,
            response.totalTokens,
            aiConfig.similarIssueMaxDisplayPatterns,
        )
    }

    private suspend fun loadHistory(context: AiAnalyzerContext): List<MergeRequestSearchHit>? {
        val services = RunScopedServices.current()
        val search = services?.get(RunServiceKeys.MERGE_REQUEST_SEARCH) ?: return null
        val keywords = SimilarIssuesHistory.keywords(context.mr)
        if (keywords.isEmpty()) return emptyList()
        val cache = services[RunServiceKeys.CACHE_PROVIDER]?.getStringCache(SimilarIssuesHistory.CACHE_NAME)
        return SimilarIssuesHistory.load(context.mr, keywords, search, cache, context.logger)
    }

    internal fun buildPrompt(
        mr: MergeRequest,
        history: List<MergeRequestSearchHit> = emptyList(),
    ): String {
        val sb = StringBuilder()

        sb.append(PromptSections.mergeRequest(mr))

        sb.appendLine("## Changed Files")
        for (change in mr.changes) {
            sb.appendLine("- ${change.path}")
        }
        sb.appendLine()

        sb.appendLine("## Commits")
        for (commit in mr.commits) {
            sb.appendLine("- ${commit.message.lines().first()}")
        }
        sb.appendLine()

        sb.appendLine("## Past Merge Requests (platform search)")
        for (hit in history) {
            sb.appendLine("- !${hit.id} [${hit.state}] ${SecretRedactor.redactText(hit.title)}")
        }

        return sb.toString()
    }

    internal fun parseResponse(
        content: String,
        aiModel: String,
        tokensUsed: Int,
        maxDisplayPatterns: Int = AiRuleConfig.DEFAULT_SIMILAR_ISSUE_MAX_DISPLAY_PATTERNS,
    ): List<Finding> {
        return try {
            val jsonStr = AiRuleAnalyzerUtils.extractJson(content)
            val result = json.decodeFromString<SimilarIssuesResult>(jsonStr)

            if (result.patterns.isEmpty()) {
                return emptyList()
            }

            val sortedPatterns = result.patterns.sortedByDescending { it.confidence }
            val topPatterns = sortedPatterns.take(maxDisplayPatterns)

            val message = buildString {
                append("Found ${result.patterns.size} potential related pattern")
                if (result.patterns.size != 1) append("s")
                append(": ")
                append(topPatterns.joinToString("; ") { it.description })
            }

            listOf(
                Finding(
                    severity = Severity.INFO,
                    message = message,
                    rule = analyzerId,
                    metadata = mapOf(
                        AiFindingMetadataKeys.RELATED_PATTERNS to json.encodeToString(result.patterns),
                        AiFindingMetadataKeys.SEARCH_TERMS to result.searchTerms.joinToString(", "),
                    ),
                )
            )
        } catch (ignored: Exception) {
            // boundary: parsing untrusted LLM output
            ignored.rethrowCancellation()
            AiRuleAnalyzerUtils.parseErrorFinding(
                ruleId = analyzerId,
                content = content,
                aiModel = aiModel,
                tokensUsed = tokensUsed,
                prefix = "AI similar issue analysis",
            )
        }
    }
}

@Serializable
internal data class SimilarIssuesResult(
    val patterns: List<RelatedPattern>,
    val searchTerms: List<String>,
)

@Serializable
internal data class RelatedPattern(
    val description: String,
    val confidence: Double,
)
