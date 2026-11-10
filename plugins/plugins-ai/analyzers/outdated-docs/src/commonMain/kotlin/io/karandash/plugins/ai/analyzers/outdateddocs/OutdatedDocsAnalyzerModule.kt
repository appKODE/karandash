@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.analyzers.outdateddocs

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunServiceKeys
import io.karandash.models.ChangeType
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AbstractAiAnalyzer
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.tokensFor
import io.karandash.plugins.ai.prompts.AnalyzerPrompts
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Detects documentation files that may be outdated relative to code changes in the MR,
 * flagging stale references and missing doc updates.
 *
 * the docs near the changed code ([DocsAtSource]) are read at the source SHA and put in the prompt; every
 * issue is a WARNING on one of those docs (or a doc the MR changes), and an unreadable reply is a parse-error finding.
 */
class OutdatedDocsAnalyzerModule : AbstractAiAnalyzer(ANALYZER_ID, "outdatedDocs") {
    override val id = "ai-outdated-docs-analyzer"
    override val name = "AI Outdated Docs"
    override val resultSerializer: KSerializer<*> = OutdatedDocsResult.serializer()
    override val defaultEnabled: Boolean = false
    override val estimatedTokens = 1500
    override val defaultPrompt: String = AnalyzerPrompts.OUTDATED_DOCS
    override val responseFormatSuffix: String = RESPONSE_FORMAT_SUFFIX
    override val explanation =
        "Detects documentation files that may be outdated relative to code changes in the MR, flagging stale references and missing doc updates."

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        val codeChanges = context.mr.changes.filter { !isDocFile(it.path) }
        if (codeChanges.isEmpty()) {
            return context.skip("no input data (no non-documentation code changes)")
        }

        val docs = DocsAtSource.load(
            context.mr,
            codeChanges.map { it.path },
            RunScopedServices.current()?.get(RunServiceKeys.REPOSITORY_FILE)
        )
        val mrDocs = context.mr.changes.filter { isDocFile(it.path) && it.changeType != ChangeType.DELETED }
        if (docs.isEmpty() && mrDocs.isEmpty()) {
            return context.skip("no documentation near the changed code could be read at the source SHA")
        }
        val knownDocs = docs.keys + mrDocs.map { it.path }

        val aiConfig = AiRuleConfig.fromPluginConfig(context.config)
        val tokens = aiConfig.tokensFor(featureKey)
        val systemPrompt = AiRuleAnalyzerUtils.effectivePrompt(
            defaultPrompt,
            context.extraInstructions,
            context.resolvedPrompt,
            responseFormatSuffix,
        )
        val prompt = buildPrompt(context, docs)

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

        return parseResponse(response.content, response.model, response.totalTokens, knownDocs)
    }

    internal fun buildPrompt(
        context: AiAnalyzerContext,
        docs: Map<String, String> = emptyMap(),
    ): String {
        val mr = context.mr
        return buildString {
            appendLine("Analyze the following merge request for outdated documentation:")
            appendLine()
            appendLine("**MR Title:** ${mr.title}")
            mr.description?.let {
                appendLine()
                appendLine("**MR Description:** $it")
            }
            appendLine()

            val docChanges = mr.changes.filter { isDocFile(it.path) }
            val codeChanges = mr.changes.filter { !isDocFile(it.path) }

            appendLine("**Documentation files in this MR (${docChanges.size}):**")
            if (docChanges.isEmpty()) {
                appendLine("(none)")
            } else {
                docChanges.take(MAX_FILES_IN_PROMPT).forEach { change ->
                    appendLine("- ${change.path} (${change.changeType})")
                }
            }
            appendLine()

            appendLine("**Code files changed (${codeChanges.size}):**")
            codeChanges.take(MAX_FILES_IN_PROMPT).forEach { change ->
                appendLine("- ${change.path} (${change.changeType}, +${change.additions}/-${change.deletions})")
            }
            if (codeChanges.size > MAX_FILES_IN_PROMPT) {
                appendLine("- ... and ${codeChanges.size - MAX_FILES_IN_PROMPT} more files")
            }
            appendLine()

            appendLine("**Documentation near the changed code, at the source SHA (${docs.size}):**")
            docs.forEach { (path, content) ->
                appendLine("--- $path ---")
                appendLine(content)
            }
            appendLine()

            appendLine("**Diffs (truncated):**")
            append(context.diffContext.preparedDiff)
        }
    }

    internal fun parseResponse(
        content: String,
        aiModel: String,
        tokensUsed: Int,
        knownDocs: Set<String>,
    ): List<Finding> {
        val jsonStr = AiRuleAnalyzerUtils.extractJson(content)

        val result = try {
            json.decodeFromString<OutdatedDocsResult>(jsonStr)
        } catch (e: IllegalArgumentException) {
            return AiRuleAnalyzerUtils.parseErrorFinding(
                ruleId = analyzerId,
                content = content,
                aiModel = aiModel,
                tokensUsed = tokensUsed,
                prefix = "AI outdated-docs analysis",
                parseException = e.message,
            )
        }

        return result.issues.filter { it.file in knownDocs }.map { issue ->
            Finding(
                severity = Severity.WARNING,
                message = issue.message,
                rule = analyzerId,
                location = Location.FileRef(path = issue.file),
                suggestion = issue.suggestion,
                explanation = issue.explanation,
                metadata = mapOf(
                    AiFindingMetadataKeys.CONFIDENCE to issue.confidence.toString(),
                ),
            )
        }
    }

    /** Returns true when [path] has a documentation file extension. */
    internal fun isDocFile(path: String): Boolean {
        val lower = path.lowercase()
        return DOC_EXTENSIONS.any { lower.endsWith(it) }
    }

    companion object {
        const val ANALYZER_ID = "outdated-docs"

        private const val MAX_FILES_IN_PROMPT = 30

        /** File extensions considered documentation files. */
        val DOC_EXTENSIONS = setOf(
            ".md",
            ".mdx",
            ".rst",
            ".txt",
            ".adoc",
            ".asciidoc",
        )

        private val json = KarandashJson.Default

        private val RESPONSE_FORMAT_SUFFIX = """
            |{
            |  "issues": [
            |    {
            |      "file": "docs/api.md",
            |      "message": "description of what is outdated",
            |            |      "confidence": 0.85,
            |      "suggestion": "how to fix the documentation",
            |      "explanation": "why this documentation is stale"
            |    }
            |  ]
            |}
        """.trimMargin()
    }
}

@Serializable
internal data class OutdatedDocsResult(
    val issues: List<OutdatedDocIssue> = emptyList(),
)

@Serializable
internal data class OutdatedDocIssue(
    val file: String,
    val message: String,
    val severity: String = "warning",
    val confidence: Double = 0.5,
    val suggestion: String? = null,
    val explanation: String? = null,
)
