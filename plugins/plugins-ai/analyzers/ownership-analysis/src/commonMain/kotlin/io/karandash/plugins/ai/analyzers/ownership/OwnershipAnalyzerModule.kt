package io.karandash.plugins.ai.analyzers.ownership

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.models.Commit
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AbstractAiAnalyzer
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.PromptSections
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.tokensFor
import io.karandash.plugins.ai.prompts.AnalyzerPrompts
import io.karandash.utils.finding.FindingFactory
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.math.roundToInt

/**
 * AI-powered code ownership analyzer, restored from the pre-`a1fec57dc` `OwnershipAnalyzer`.
 *
 * Sends per-file blame summaries (`FileChange.blameData`, fetched by the pipeline because of [needsBlame]),
 * keyword-detected bug-fix commits and the prepared diff to the model, then reports an ownership
 * score, cross-team changes and up to [MAX_BUG_FIX_FINDINGS] bug-fix correlations. Severity:
 * score `< ownershipError` or 3+ bug fixes is ERROR; score `< ownershipWarning` or cross-team
 * changes is WARNING; otherwise INFO.
 *
 * Default off: runs only when `ai.features.ownershipAnalysis: true` is configured.
 */
class OwnershipAnalyzerModule : AbstractAiAnalyzer(ANALYZER_ID, FEATURE_KEY) {
    override val id: String get() = ID
    override val name: String get() = "AI Ownership Analysis"
    override val mrLevel: Boolean get() = true
    override val resultSerializer: KSerializer<*> = OwnershipResult.serializer()
    override val defaultEnabled: Boolean get() = false
    override val needsBlame: Boolean get() = true
    override val estimatedTokens: Int get() = ESTIMATED_TOKENS
    override val defaultPrompt: String? get() = AnalyzerPrompts.OWNERSHIP_ANALYSIS
    override val explanation: String get() =
        "Analyzes code ownership from blame data: ownership concentration, cross-team changes, " +
            "bug-fix correlations and knowledge risks."

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        val aiConfig = AiRuleConfig.fromPluginConfig(context.config)
        val tokens = aiConfig.tokensFor(featureKey)
        val prompt = buildPrompt(context.mr, context.diffContext.preparedDiff)
        val systemPrompt = AiRuleAnalyzerUtils.effectivePrompt(
            AnalyzerPrompts.OWNERSHIP_ANALYSIS,
            context.extraInstructions,
            context.resolvedPrompt,
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

        return parseResponse(response.content, response.model, response.totalTokens, aiConfig)
    }

    internal fun buildPrompt(
        mr: MergeRequest,
        preparedDiff: String,
    ): String =
        buildString {
            // no author email reaches the model
            append(PromptSections.mergeRequest(mr, "Author" to mr.author.name))
            if (mr.reviewers.isNotEmpty()) {
                appendLine("## Reviewers")
                mr.reviewers.forEach { appendLine("- ${it.name} (${it.username})") }
                appendLine()
            }
            if (mr.approvals.isNotEmpty()) {
                appendLine("## Approvers")
                mr.approvals.forEach { appendLine("- ${it.user.name} (${it.user.username})") }
                appendLine()
            }
            appendLine("## Changed Files (${mr.changes.size} total)")
            mr.changes.forEach { appendLine("- ${it.path} [${it.changeType}] (+${it.additions}/-${it.deletions})") }
            appendLine()
            appendBlameSection(mr)
            appendBugFixSection(mr.commits)
            appendLine("## Commits (${mr.commits.size} total)")
            mr.commits.take(MAX_COMMITS_IN_PROMPT).forEach { appendLine("- ${it.author.name}: ${it.message.lines().first()}") }
            appendLine()
            appendLine("## Diff Previews")
            append(preparedDiff)
        }

    private fun StringBuilder.appendBlameSection(mr: MergeRequest) {
        val filesWithBlame = mr.changes.filter { it.blameData.isNotEmpty() }
        if (filesWithBlame.isEmpty()) {
            appendLine("## Blame Data")
            appendLine("No blame data available for changed files.")
            appendLine()
            return
        }
        appendLine("## Blame Data (${filesWithBlame.size} files with blame)")
        filesWithBlame.forEach { change ->
            appendLine("### ${change.path}")
            change.blameData
                // group by name only; author emails never reach the model
                .groupBy { it.commit.author.name }
                .mapValues { (_, entries) -> entries.sumOf { if (it.endLine > 0) it.endLine - it.startLine + 1 else 1 } }
                .entries
                .sortedByDescending { it.value }
                .forEach { (author, lines) -> appendLine("  - $author: $lines lines") }
        }
        appendLine()
    }

    private fun StringBuilder.appendBugFixSection(commits: List<Commit>) {
        val signals = detectBugFixCommits(commits)
        if (signals.isEmpty()) return
        appendLine("## Bug-Fix Signals (${signals.size} commits)")
        signals.forEach { signal ->
            val firstLine = signal.commit.message
                .lines()
                .first()
            appendLine("- ${signal.commit.author.name}: \"$firstLine\" [keywords: ${signal.keywords.joinToString(", ")}]")
        }
        appendLine()
    }

    /** Commits whose messages contain bug-fix keywords (case-insensitive). */
    internal fun detectBugFixCommits(commits: List<Commit>): List<BugFixSignal> =
        commits.mapNotNull { commit ->
            val lowerMessage = commit.message.lowercase()
            val matched = BUG_FIX_PATTERNS.filter { lowerMessage.contains(it) }
            if (matched.isNotEmpty()) BugFixSignal(commit, matched) else null
        }

    internal fun parseResponse(
        content: String,
        aiModel: String,
        tokensUsed: Int,
        aiConfig: AiRuleConfig = AiRuleConfig(),
    ): List<Finding> {
        val result = try {
            KarandashJson.Default.decodeFromString<OwnershipResult>(AiRuleAnalyzerUtils.extractJson(content))
        } catch (e: SerializationException) {
            return parseError(content, aiModel, tokensUsed, e)
        } catch (e: IllegalArgumentException) {
            return parseError(content, aiModel, tokensUsed, e)
        }
        return listOf(summaryFinding(result, aiConfig)) +
            result.bugFixCorrelations.take(MAX_BUG_FIX_FINDINGS).map(::bugFixFinding)
    }

    private fun parseError(
        content: String,
        aiModel: String,
        tokensUsed: Int,
        e: Exception,
    ): List<Finding> =
        AiRuleAnalyzerUtils.parseErrorFinding(
            ruleId = ANALYZER_ID,
            content = content,
            aiModel = aiModel,
            tokensUsed = tokensUsed,
            prefix = "AI ownership analysis",
            parseException = e.message,
        )

    private fun summaryFinding(
        result: OwnershipResult,
        aiConfig: AiRuleConfig,
    ): Finding {
        val stats = result.statistics
        val score = result.ownershipScore
        // capped at WARNING; an ownership score describes people, not a defect in the change
        val threshold = maxOf(aiConfig.ownershipWarningThreshold, aiConfig.ownershipErrorThreshold)
        val severity = if (score < threshold || result.crossTeamChanges) Severity.WARNING else Severity.INFO
        val metadata = buildMap {
            put(OWNERSHIP_SCORE_KEY, score.toString())
            put("crossTeamChanges", result.crossTeamChanges.toString())
            put("uniqueContributors", result.contributors.size.toString())
            put("uniqueCodeOwners", stats.uniqueCodeOwners.toString())
            put("bugFixCount", stats.bugFixCount.toString())
            put("crossTeamAreas", stats.crossTeamChangeCount.toString())
            put("filesWithBlameData", stats.filesWithBlameData.toString())
            put("totalFilesAnalyzed", stats.totalFilesAnalyzed.toString())
            put("knowledgeRisks", result.knowledgeRisks.joinToString("; "))
        }
        return Finding(
            severity = severity,
            rule = ANALYZER_ID,
            message = "Ownership analysis (score: ${(score * 100).roundToInt() / 100.0}): ${result.summary}",
            metadata = metadata,
        )
    }

    private fun bugFixFinding(corr: BugFixCorrelation): Finding =
        FindingFactory.warn(
            rule = ANALYZER_ID,
            // the finding names the file, never who introduced or fixed the bug
            message = "Bug-fix pattern in ${corr.file}: this change fixes code from an earlier commit " +
                "(confidence: ${(corr.confidence * 100).roundToInt()}%)",
            metadata = mapOf(AiFindingMetadataKeys.FILE to corr.file),
        )

    companion object {
        /** Module ID used for registration and lookup. */
        const val ID = "ai-ownership-analysis-analyzer"

        /** Analyzer rule ID used in findings and config. */
        const val ANALYZER_ID = "ownership-analysis"

        /** Key under `ai.features` that enables this analyzer. */
        const val FEATURE_KEY = "ownershipAnalysis"

        /** Summary-finding metadata key carrying the ownership score. */
        const val OWNERSHIP_SCORE_KEY = "ownershipScore"

        private const val ESTIMATED_TOKENS = 3000
        private const val MAX_COMMITS_IN_PROMPT = 15
        private const val MAX_BUG_FIX_FINDINGS = 5
        private val BUG_FIX_PATTERNS = listOf("fix", "bug", "revert", "hotfix", "patch", "resolve", "regression")
    }
}

/** A commit whose message matched one or more bug-fix keywords. */
@Serializable
internal data class BugFixSignal(
    val commit: Commit,
    val keywords: List<String>,
)
