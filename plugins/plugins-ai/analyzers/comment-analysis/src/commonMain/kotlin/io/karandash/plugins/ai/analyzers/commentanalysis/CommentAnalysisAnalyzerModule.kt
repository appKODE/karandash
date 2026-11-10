@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.analyzers.commentanalysis

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.finding.ReviewThread
import io.karandash.api.run.MergeRequestThreads
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunServiceKeys
import io.karandash.api.support.rethrowCancellation
import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AbstractAiAnalyzer
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.SecretRedactor
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.tokensFor
import io.karandash.plugins.ai.prompts.AnalyzerPrompts
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * `review-thread-analysis`: analyzes the
 * MR's open review threads ([RunServiceKeys.REVIEW_THREADS]), description and commit messages for discussion quality
 * issues.
 *
 * Runs only when the MR has an open thread that people started: without one it skips with no AI call, and threads the
 * bot opened (its account or its inline-finding marker) do not count.
 *
 * Detects: unresolved review threads, unresolved questions, action items, ambiguous statements, TODOs,
 * missing context, and stale markers that indicate incomplete or unclear communication.
 */
class CommentAnalysisAnalyzerModule : AbstractAiAnalyzer(ANALYZER_ID, "reviewThreadAnalysis") {
    override val id: String = "ai-review-thread-analysis-analyzer"
    override val name: String = "AI Review Thread Analysis"

    // threads and the description belong to the whole MR: one call, not one per diff chunk repeating them
    override val mrLevel: Boolean get() = true
    override val resultSerializer: KSerializer<*> = CommentAnalysisResult.serializer()
    override val defaultEnabled: Boolean = false
    override val estimatedTokens: Int = 1200
    override val defaultPrompt: String = AnalyzerPrompts.COMMENT_ANALYSIS
    override val responseFormatSuffix: String = RESPONSE_FORMAT_SUFFIX
    override val explanation: String =
        "Analyzes the MR's open review threads, description and commit messages for discussion quality issues: " +
            "unresolved threads and questions, stale action items, ambiguous statements, and vague commit messages."

    companion object {
        const val ANALYZER_ID = "review-thread-analysis"
        private const val MAX_COMMITS_IN_PROMPT = 15
        private const val MAX_THREADS_IN_PROMPT = 15
        private const val MAX_NOTE_CHARS = 300

        private val RESPONSE_FORMAT_SUFFIX = """
            |{
            |  "issues": [
            |    {
            |      "message": "description of the quality issue found",
            |      "category": "unresolved_thread|unresolved_question|stale_action_item|ambiguous_statement|missing_context|vague_commit",
            |      "severity": "warning|info",
            |      "confidence": 0.9,
            |      "suggestion": "how to resolve this issue",
            |      "explanation": "why this matters for review quality"
            |    }
            |  ]
            |}
        """.trimMargin()
    }

    private val json = KarandashJson.Default

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        val mr = context.mr
        val threads = openThreads(context.logger)
        // Without a discussion there is nothing to judge; the description and commits alone are not worth a call
        if (threads.isEmpty()) return context.skip("no open review threads")

        val aiConfig = AiRuleConfig.fromPluginConfig(context.config)
        val tokens = aiConfig.tokensFor(featureKey)
        val prompt = buildPrompt(mr, threads)
        context.logger.debug(
            buildString {
                append("AI plugin [$analyzerId]: prompt data — ")
                append("title=${mr.title.length} chars, ")
                append("description=${mr.description?.length ?: 0} chars")
                append(", commits=${mr.commits.size}")
                append(", openThreads=${threads.size}")
                append(", totalPrompt=${prompt.length} chars")
                append(" (no diff data sent)")
            }
        )
        val systemPrompt = AiRuleAnalyzerUtils.effectivePrompt(
            defaultPrompt,
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
        return parseResponse(response.content, response.model, response.totalTokens)
    }

    /**
     * The run MR's unresolved threads without platform-generated notes and without the bot's own threads (started by
     * the bot account or carrying its inline-finding marker); empty when the platform cannot list them.
     */
    private suspend fun openThreads(logger: Logger): List<ReviewThread> {
        val service = RunScopedServices.current()?.get(RunServiceKeys.REVIEW_THREADS) ?: return emptyList()
        val open = service
            .list()
            .orEmpty()
            .filter { !it.resolved }
            .map { thread -> thread.copy(notes = thread.notes.filter { !it.system }) }
            .filter { it.notes.isNotEmpty() }
        if (open.isEmpty()) return open
        val bot = botUsername(service, logger)
        return open.filterNot { thread ->
            val first = thread.notes.first()
            (bot != null && first.author == bot) || InlineFindingMarker.carriesFindings(first.body)
        }
    }

    @Suppress("TooGenericExceptionCaught") // boundary: an unknown bot identity leaves only the marker check
    private suspend fun botUsername(
        threads: MergeRequestThreads,
        logger: Logger,
    ): String? =
        try {
            threads.botUsername()
        } catch (e: Exception) {
            // boundary: platform API call; bot threads are then recognised by their inline-finding marker only
            e.rethrowCancellation()
            logger.warn("Could not read the bot's username, recognising bot threads by their marker only: ${e.message}")
            null
        }

    internal fun buildPrompt(
        mr: MergeRequest,
        threads: List<ReviewThread> = emptyList(),
    ): String =
        buildString {
            appendLine("Analyze the following merge request for discussion quality issues:")
            appendLine()
            appendLine("**MR Title:** ${mr.title}")
            appendLine()

            val description = mr.description
            if (!description.isNullOrBlank()) {
                appendLine("**MR Description:**")
                appendLine(description)
                appendLine()
            }

            if (mr.commits.isNotEmpty()) {
                appendLine("**Commit messages (${mr.commits.size}):**")
                mr.commits.take(MAX_COMMITS_IN_PROMPT).forEach { commit ->
                    appendLine("- ${commit.message.lines().first()}")
                }
                if (mr.commits.size > MAX_COMMITS_IN_PROMPT) {
                    appendLine("- ... and ${mr.commits.size - MAX_COMMITS_IN_PROMPT} more")
                }
            }

            if (threads.isNotEmpty()) {
                appendLine()
                appendLine("**Open review threads (${threads.size}):**")
                threads.take(MAX_THREADS_IN_PROMPT).forEach { thread ->
                    val first = thread.notes.first()
                    val note = SecretRedactor.redactText(first.body).take(MAX_NOTE_CHARS)
                    appendLine("- [${first.author ?: "unknown"}] $note (${thread.notes.size - 1} replies)")
                }
            }
        }

    internal fun parseResponse(
        content: String,
        aiModel: String,
        tokensUsed: Int,
    ): List<Finding> {
        val jsonStr = AiRuleAnalyzerUtils.extractJson(content)

        val result = try {
            json.decodeFromString<CommentAnalysisResult>(jsonStr)
        } catch (e: IllegalArgumentException) {
            return AiRuleAnalyzerUtils.parseErrorFinding(
                ruleId = analyzerId,
                content = content,
                aiModel = aiModel,
                tokensUsed = tokensUsed,
                prefix = "AI review thread analysis",
                parseException = e.message,
            )
        }

        return result.issues.map { issue ->
            val severity = Severity.fromString(issue.severity)
            Finding(
                severity = severity,
                rule = analyzerId,
                message = issue.message,
                suggestion = issue.suggestion,
                explanation = issue.explanation,
                metadata = mapOf(
                    AiFindingMetadataKeys.CATEGORY to issue.category,
                    AiFindingMetadataKeys.CONFIDENCE to issue.confidence.toString(),
                ),
            )
        }
    }
}

@Serializable
internal data class CommentAnalysisResult(
    val issues: List<CommentIssue> = emptyList(),
)

@Serializable
internal data class CommentIssue(
    val message: String,
    val category: String,
    val severity: String = "info",
    val confidence: Double = 0.5,
    val suggestion: String? = null,
    val explanation: String? = null,
)
