package io.karandash.plugins.ai.core.pipeline

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiService
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AiDefaultModels
import io.karandash.plugins.ai.core.isAiFinding
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import kotlinx.serialization.Serializable

/**
 * Constitutional AI reviewer: applies user-defined review principles to AI findings.
 *
 * After AI produces findings, this reviewer evaluates each finding against a set of
 * constitutional principles. Findings that violate principles are removed.
 *
 * Example principles:
 * - "Never comment on code formatting - linters handle that"
 * - "Only flag issues you are >90% confident about"
 * - "Always cite the specific line number and code snippet"
 * - "Prefer actionable suggestions over vague concerns"
 *
 * On any AI service failure or parse failure the original findings are returned
 * unchanged, so this stage never causes a validation pipeline failure on its own.
 *
 * @param aiService The AI service for self-critique calls.
 * @param principles List of constitutional principles to enforce.
 * @param model Model to use for self-critique.
 * @param selfCritiqueRounds Number of self-critique passes (1 is usually sufficient).
 * @param downgradeViolations When true, violating findings are kept as INFO with a `[CONSTITUTION]`
 *   prefix instead of being removed; [ConstitutionalResult.removedCount] then counts them.
 */
class ConstitutionalReviewer(
    private val aiService: AiService,
    private val principles: List<String>,
    private val model: String = DEFAULT_MODEL,
    private val selfCritiqueRounds: Int = 1,
    private val downgradeViolations: Boolean = false,
) {
    /**
     * Reviews AI findings against constitutional principles.
     *
     * Non-AI findings (not [isAiFinding]) pass through unchanged.
     * If [principles] is empty or [findings] is empty, returns immediately without an AI call.
     *
     * @param findings AI-generated and non-AI findings to review.
     * @return [ConstitutionalResult] with filtered findings and review metadata.
     */
    suspend fun review(findings: List<Finding>): ConstitutionalResult {
        if (principles.isEmpty() || findings.isEmpty()) {
            return ConstitutionalResult(findings = findings, removedCount = 0, totalReviewed = 0)
        }

        val aiFindings = findings.filter { it.isAiFinding() }
        val nonAiFindings = findings.filter { !it.isAiFinding() }

        if (aiFindings.isEmpty()) {
            return ConstitutionalResult(findings = findings, removedCount = 0, totalReviewed = 0)
        }

        var currentFindings = aiFindings
        var totalRemoved = 0

        repeat(selfCritiqueRounds) {
            val result = evaluateRound(currentFindings)
            totalRemoved += result.removedCount
            currentFindings = result.findings
        }

        return ConstitutionalResult(
            findings = nonAiFindings + currentFindings,
            removedCount = totalRemoved,
            totalReviewed = aiFindings.size,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun evaluateRound(findings: List<Finding>): ConstitutionalResult {
        val prompt = buildCritiquePrompt(findings)

        val response = try {
            aiService.complete(
                AiCompletionRequest(
                    prompt = prompt,
                    systemPrompt = SYSTEM_PROMPT,
                    model = model,
                    maxTokens = MAX_CRITIQUE_TOKENS,
                    temperature = CRITIQUE_TEMPERATURE,
                    jsonMode = true,
                ),
            )
        } catch (e: Exception) {
            // boundary: LLM provider call / untrusted LLM output
            e.rethrowCancellation()
            return ConstitutionalResult(findings = findings, removedCount = 0, totalReviewed = findings.size)
        }

        return parseCritiqueResponse(response.content, findings)
    }

    private fun buildCritiquePrompt(findings: List<Finding>): String =
        buildString {
            appendLine("## Constitutional Principles")
            appendLine()
            for ((i, principle) in principles.withIndex()) {
                appendLine("${i + 1}. $principle")
            }
            appendLine()
            appendLine("## Findings to Review")
            appendLine()
            for ((i, finding) in findings.withIndex()) {
                appendLine("### Finding $i")
                appendLine("- Rule: ${finding.rule}")
                appendLine("- Severity: ${finding.severity}")
                appendLine("- Message: ${finding.message}")
                finding.explanation?.let { appendLine("- Explanation: $it") }
                appendLine()
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun parseCritiqueResponse(
        content: String,
        findings: List<Finding>,
    ): ConstitutionalResult =
        try {
            val jsonStr = AiRuleAnalyzerUtils.extractJson(content)
            val response = json.decodeFromString<CritiqueResponse>(jsonStr)

            val keepIndices = response.findings
                .filter { it.verdict == "keep" }
                .map { it.index }
                .toSet()

            val filtered = if (downgradeViolations) {
                findings.mapIndexed { idx, f -> if (idx in keepIndices) f else downgrade(f) }
            } else {
                findings.filterIndexed { idx, _ -> idx in keepIndices }
            }
            val removed = findings.indices.count { it !in keepIndices }

            ConstitutionalResult(
                findings = filtered,
                removedCount = removed,
                totalReviewed = findings.size,
            )
        } catch (e: Exception) {
            // boundary: LLM provider call / untrusted LLM output
            e.rethrowCancellation()
            ConstitutionalResult(findings = findings, removedCount = 0, totalReviewed = findings.size)
        }

    private fun downgrade(finding: Finding): Finding =
        if (finding.message.startsWith(VIOLATION_PREFIX)) {
            finding
        } else {
            finding.copy(severity = Severity.INFO, message = "$VIOLATION_PREFIX ${finding.message}")
        }

    companion object {
        private const val VIOLATION_PREFIX = "[CONSTITUTION]"

        /** Default model for self-critique; intentionally cheap since it is a filtering task. */
        const val DEFAULT_MODEL = AiDefaultModels.GENERIC
        private const val MAX_CRITIQUE_TOKENS = 1500
        private const val CRITIQUE_TEMPERATURE = 0.1

        private val json = KarandashJson.Default

        private val SYSTEM_PROMPT = """
You are a constitutional AI reviewer. Given a set of code review findings and a list of
constitutional principles, evaluate each finding against the principles.

For each finding, respond with:
- "keep" if the finding respects ALL principles
- "remove" if the finding violates ANY principle, with the violated principle number

Respond with ONLY valid JSON:
{
  "findings": [
    {"index": 0, "verdict": "keep"},
    {"index": 1, "verdict": "remove", "violatedPrinciple": 3, "reason": "Comments on formatting"}
  ]
}
        """.trimIndent()
    }
}

@Serializable
internal data class CritiqueResponse(
    val findings: List<CritiqueFindingVerdict> = emptyList(),
)

@Serializable
internal data class CritiqueFindingVerdict(
    val index: Int,
    val verdict: String,
    val violatedPrinciple: Int? = null,
    val reason: String? = null,
)

/**
 * Result of constitutional AI review.
 *
 * @param findings Filtered findings after applying constitutional principles.
 * @param removedCount Number of findings removed by the review.
 * @param totalReviewed Total number of AI findings evaluated.
 */
@Serializable
data class ConstitutionalResult(
    val findings: List<Finding>,
    val removedCount: Int,
    val totalReviewed: Int,
)
