package io.karandash.plugins.ai.analyzers.codequality

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.models.support.WellKnownMetadataKeys
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
import kotlinx.serialization.SerializationException
import kotlin.math.roundToInt

/**
 * AI-powered code quality scorer, restored from the pre-`a1fec57dc` `CodeQualityAnalyzer`.
 *
 * Scores the merge request's code changes across seven dimensions (correctness, error handling,
 * readability, maintainability, performance, security, design) and emits one summary finding plus
 * up to [MAX_ISSUES] per-issue findings. Lower scores map to higher severities:
 * `<= codeQualityError` is ERROR, `<= codeQualityWarning` is WARNING, otherwise INFO.
 *
 * Default off: runs only when `ai.features.codeQuality: true` is configured.
 */
class CodeQualityAnalyzerModule : AbstractAiAnalyzer(ANALYZER_ID, FEATURE_KEY) {
    override val id: String get() = ID
    override val name: String get() = "AI Code Quality"
    override val resultSerializer: KSerializer<*> = CodeQualityResult.serializer()
    override val defaultEnabled: Boolean get() = false
    override val estimatedTokens: Int get() = ESTIMATED_TOKENS
    override val needsScopeDetection: Boolean get() = true
    override val defaultPrompt: String? get() = AnalyzerPrompts.CODE_QUALITY
    override val explanation: String get() =
        "Scores code quality across 7 dimensions (correctness, error handling, readability, maintainability, " +
            "performance, security, design) and lists the most impactful issues."

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        val aiConfig = AiRuleConfig.fromPluginConfig(context.config)
        val tokens = aiConfig.tokensFor(featureKey)
        val prompt = buildPrompt(context)
        val basePrompt = if (aiConfig.codeQualityIncludeMaturityLevel) {
            AnalyzerPrompts.CODE_QUALITY + "\n\n" + MATURITY_SUPPLEMENT
        } else {
            AnalyzerPrompts.CODE_QUALITY
        }
        val systemPrompt = AiRuleAnalyzerUtils.effectivePrompt(basePrompt, context.extraInstructions, context.resolvedPrompt)

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

    internal fun buildPrompt(context: AiAnalyzerContext): String =
        buildString {
            val mr = context.mr
            append(PromptSections.mergeRequest(mr, "Labels" to mr.labels.joinToString(", ").ifEmpty { "(none)" }))
            appendLine("## Changed Files (${mr.changes.size} total)")
            mr.changes.forEach { appendLine("- ${it.path} [${it.changeType}] (+${it.additions}/-${it.deletions})") }
            appendLine()
            appendLine("## Commits (${mr.commits.size} total)")
            mr.commits.take(MAX_COMMITS_IN_PROMPT).forEach { appendLine("- ${it.message.lines().first()}") }
            appendLine()
            appendLine("## Diff Previews")
            append(context.diffContext.preparedDiff)
        }

    internal fun parseResponse(
        content: String,
        aiModel: String,
        tokensUsed: Int,
        aiConfig: AiRuleConfig = AiRuleConfig(),
    ): List<Finding> {
        val result = try {
            KarandashJson.Default.decodeFromString<CodeQualityResult>(AiRuleAnalyzerUtils.extractJson(content))
        } catch (e: SerializationException) {
            return parseError(content, aiModel, tokensUsed, e)
        } catch (e: IllegalArgumentException) {
            return parseError(content, aiModel, tokensUsed, e)
        }
        return listOf(summaryFinding(result, aiConfig)) + result.issues.take(MAX_ISSUES).map(::issueFinding)
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
            prefix = "AI code quality analysis",
            parseException = e.message,
        )

    private fun summaryFinding(
        result: CodeQualityResult,
        aiConfig: AiRuleConfig,
    ): Finding {
        val score = result.overallScore
        val errorThreshold = aiConfig.codeQualityErrorThreshold
        val warningThreshold = aiConfig.codeQualityWarningThreshold
        val (severity, qualityLevel) = when {
            score <= errorThreshold -> Severity.ERROR to "poor"
            score <= warningThreshold -> Severity.WARNING to "needs improvement"
            score <= GOOD_SCORE_CEILING -> Severity.INFO to "good"
            else -> Severity.INFO to "excellent"
        }
        val scoreDisplay = (score * 100).roundToInt() / 100.0
        val dims = result.dimensions
        val metadata = buildMap {
            put(OVERALL_SCORE_KEY, score.toString())
            put("correctness", dims.correctness.toString())
            put("errorHandling", dims.errorHandling.toString())
            put("readability", dims.readability.toString())
            put("maintainability", dims.maintainability.toString())
            put("performance", dims.performance.toString())
            put("security", dims.security.toString())
            put("design", dims.design.toString())
            put("strengths", result.strengths.joinToString("; "))
            val maturity = result.codeMaturityLevel
            if (aiConfig.codeQualityIncludeMaturityLevel && maturity != null) put(MATURITY_KEY, maturity)
        }
        return Finding(
            severity = severity,
            rule = ANALYZER_ID,
            message = "Code quality: $qualityLevel (score: $scoreDisplay) - ${result.summary}",
            metadata = metadata,
        )
    }

    private fun issueFinding(issue: CodeQualityIssue): Finding =
        Finding(
            severity = Severity.fromString(issue.severity),
            rule = ANALYZER_ID,
            message = "[${issue.dimension}] ${issue.message}",
            location = issue.file?.let { Location.FileRef(it, issue.line) },
            suggestion = issue.suggestion,
            metadata = mapOf(
                AiFindingMetadataKeys.DIMENSION to issue.dimension,
            ),
        )

    companion object {
        /** Module ID used for registration and lookup. */
        const val ID = "ai-code-quality-analyzer"

        /** Analyzer rule ID used in findings and config. */
        const val ANALYZER_ID = AiRuleIds.CODE_QUALITY

        /** Key under `ai.features` that enables this analyzer. */
        const val FEATURE_KEY = "codeQuality"

        /** Summary-finding metadata key carrying the overall score. */
        const val OVERALL_SCORE_KEY = WellKnownMetadataKeys.OVERALL_SCORE

        /** Summary-finding metadata key carrying the maturity level (only with `codeQualityIncludeMaturityLevel`). */
        const val MATURITY_KEY = "codeMaturityLevel"

        private const val ESTIMATED_TOKENS = 3500
        private const val MAX_COMMITS_IN_PROMPT = 10
        private const val MAX_ISSUES = 8
        private const val GOOD_SCORE_CEILING = 0.8

        private val MATURITY_SUPPLEMENT: String by lazy {
            """
Additionally, assess the code maturity level based on the patterns observed in the code changes.
This describes the CODE PATTERNS, not the developer's skill level.

Add to your JSON response:
  "codeMaturityLevel": "<junior|mid|senior|staff>"

Maturity level guide:
- junior: Basic patterns, limited error handling, simple control flow, few abstractions
- mid: Solid fundamentals, reasonable error handling, some design patterns, adequate testing awareness
- senior: Clean abstractions, comprehensive error handling, performance-aware, security-conscious, well-structured
- staff: Elegant architecture, systemic thinking, cross-cutting concerns handled, production-hardened patterns
            """.trimIndent()
        }
    }
}

@Serializable
internal data class CodeQualityResult(
    val overallScore: Double,
    val dimensions: CodeQualityDimensions,
    val issues: List<CodeQualityIssue> = emptyList(),
    val strengths: List<String> = emptyList(),
    val summary: String,
    val codeMaturityLevel: String? = null,
)

@Serializable
internal data class CodeQualityDimensions(
    val correctness: Double,
    val errorHandling: Double,
    val readability: Double,
    val maintainability: Double,
    val performance: Double,
    val security: Double,
    val design: Double,
)

@Serializable
internal data class CodeQualityIssue(
    val file: String? = null,
    val line: Int? = null,
    val dimension: String,
    val severity: String,
    val message: String,
    val suggestion: String? = null,
)
