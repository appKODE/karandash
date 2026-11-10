package io.karandash.plugins.ai.core.reporters

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiService
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiDefaultModels
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Generates audience-tailored natural language summaries of validation findings.
 *
 * Three audiences produce different tones:
 * - **developer**: Action items and code-level fixes
 * - **manager**: Risk assessment and team impact
 * - **security**: Compliance status and vulnerability summary
 *
 * @param aiService Optional AI service for enhanced summaries. When null or unavailable,
 *   a static summary is returned.
 * @param model AI model ID to use for completions.
 * @param language `ai.responseLanguage`: the language of the AI summary and of the static summary texts; null or English
 *   needs no instruction.
 */
class SmartDigestGenerator(
    private val aiService: AiService?,
    private val model: String = DEFAULT_MODEL,
    private val language: String? = null,
) {
    /**
     * Generates a digest summary for the given findings and audience.
     *
     * @param findings Validation findings to summarise.
     * @param audience Target audience that determines tone and content focus.
     * @return A [DigestResult] containing the summary and metadata.
     */
    suspend fun generate(
        findings: List<Finding>,
        audience: DigestAudience
    ): DigestResult {
        val stats = computeStats(findings)
        val staticSummary = staticSummary(stats, audience)

        if (aiService == null || !aiService.isAvailable) {
            return DigestResult(summary = staticSummary, audience = audience, aiEnhanced = false)
        }

        val aiSummary = try {
            val prompt = buildPrompt(findings, audience, stats)
            val response = aiService.complete(
                AiCompletionRequest(
                    prompt = prompt,
                    systemPrompt = LanguagePromptOverlays.withOutputLanguage(systemPromptFor(audience), language),
                    model = model,
                    maxTokens = MAX_DIGEST_TOKENS,
                    temperature = DIGEST_TEMPERATURE,
                ),
            )
            response.content
        } catch (ignored: Exception) {
            // boundary: LLM provider call; digest is optional
            ignored.rethrowCancellation()
            null
        }

        return DigestResult(
            summary = aiSummary ?: staticSummary,
            audience = audience,
            aiEnhanced = aiSummary != null,
            stats = stats,
        )
    }

    /**
     * Computes aggregate statistics from a list of findings.
     *
     * @param findings The findings to aggregate.
     * @return A [DigestStats] summary.
     */
    fun computeStats(findings: List<Finding>): DigestStats {
        val bySeverity = findings.groupBy { it.severity }
        val byRule = findings.groupBy { it.rule }
        return DigestStats(
            totalFindings = findings.size,
            errors = bySeverity[Severity.ERROR]?.size ?: 0,
            warnings = bySeverity[Severity.WARNING]?.size ?: 0,
            infos = bySeverity[Severity.INFO]?.size ?: 0,
            rulesTriggered = byRule.keys.size,
            topRules = byRule.entries
                .sortedByDescending { it.value.size }
                .take(TOP_RULES_COUNT)
                .map { it.key },
            passRate = if (findings.isEmpty()) {
                1.0
            } else {
                1.0 - (bySeverity[Severity.ERROR]?.size ?: 0).toDouble() / findings.size.coerceAtLeast(1)
            },
        )
    }

    /** The summary without AI for [stats] and [audience], in the generator's language. */
    fun staticSummary(
        stats: DigestStats,
        audience: DigestAudience,
    ): String =
        when (audience) {
            DigestAudience.DEVELOPER -> buildString {
                appendLine(AiReportText.DIGEST_DEVELOPER_TITLE.of(language))
                appendLine(AiReportText.DIGEST_COUNTS.of(language, stats.totalFindings, stats.errors, stats.warnings, stats.infos))
                if (stats.topRules.isNotEmpty()) appendLine(AiReportText.DIGEST_TOP_RULES.of(language, stats.topRules.joinToString(", ")))
            }

            DigestAudience.MANAGER -> buildString {
                appendLine(AiReportText.DIGEST_MANAGER_TITLE.of(language))
                appendLine(AiReportText.DIGEST_PASS_RATE.of(language, (stats.passRate * PERCENT_MULTIPLIER).roundToInt()))
                appendLine(AiReportText.DIGEST_BLOCKING.of(language, stats.errors, stats.rulesTriggered))
            }

            DigestAudience.SECURITY -> buildString {
                appendLine(AiReportText.DIGEST_SECURITY_TITLE.of(language))
                val secFindings = stats.topRules.count { it.contains("security") || it.contains("ai-security") }
                appendLine(AiReportText.DIGEST_SECURITY_FINDINGS.of(language, secFindings))
                appendLine(AiReportText.DIGEST_TOTAL.of(language, stats.totalFindings, stats.errors))
            }
        }

    private fun buildPrompt(
        findings: List<Finding>,
        audience: DigestAudience,
        stats: DigestStats
    ): String =
        buildString {
            appendLine(
                "Summarize these ${stats.totalFindings} validation findings for a " +
                    "${audience.name.lowercase()} audience:",
            )
            for (f in findings.take(MAX_FINDINGS_IN_PROMPT)) {
                appendLine("- [${f.severity}] ${f.rule}: ${f.message.take(MAX_MESSAGE_LENGTH)}")
            }
        }

    private fun systemPromptFor(audience: DigestAudience): String =
        when (audience) {
            DigestAudience.DEVELOPER -> {
                "You are a code review assistant. Summarize findings as actionable developer tasks. Be concise."
            }

            DigestAudience.MANAGER -> {
                "You are a risk analyst. Summarize findings as a risk assessment for engineering managers. " +
                    "Focus on impact and team metrics."
            }

            DigestAudience.SECURITY -> {
                "You are a security analyst. Summarize findings focusing on compliance, vulnerabilities, " +
                    "and security posture."
            }
        }

    companion object {
        private const val DEFAULT_MODEL = AiDefaultModels.GENERIC
        private const val MAX_DIGEST_TOKENS = 500
        private const val DIGEST_TEMPERATURE = 0.3
        private const val TOP_RULES_COUNT = 5
        private const val MAX_FINDINGS_IN_PROMPT = 20
        private const val MAX_MESSAGE_LENGTH = 150
        private const val PERCENT_MULTIPLIER = 100
    }
}

/** Target audience for a [DigestResult]. */
enum class DigestAudience { DEVELOPER, MANAGER, SECURITY }

/**
 * Result of a digest generation.
 *
 * @param summary The generated summary text.
 * @param audience The audience this digest was tailored for.
 * @param aiEnhanced Whether the summary was generated by an AI model.
 * @param stats Optional aggregate statistics used to build the summary.
 */
@Serializable
data class DigestResult(
    val summary: String,
    val audience: DigestAudience,
    val aiEnhanced: Boolean,
    val stats: DigestStats? = null,
)

/**
 * Aggregate statistics computed from a list of findings.
 *
 * @param totalFindings Total number of findings.
 * @param errors Count of ERROR-severity findings.
 * @param warnings Count of WARNING-severity findings.
 * @param infos Count of INFO-severity findings.
 * @param rulesTriggered Number of distinct rules that produced findings.
 * @param topRules Rule IDs sorted by finding count descending, capped at 5.
 * @param passRate Fraction of findings that are not errors (1.0 = fully passing).
 */
@Serializable
data class DigestStats(
    val totalFindings: Int,
    val errors: Int,
    val warnings: Int,
    val infos: Int,
    val rulesTriggered: Int,
    val topRules: List<String>,
    val passRate: Double,
)
