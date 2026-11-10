package io.karandash.plugins.ai.core.reporters

import io.karandash.crypto.sha256Hex
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.serialization.Serializable

/**
 * Compliance evidence pack builder for SOC2/ISO27001 audits.
 * Produces a structured evidence record with a simple integrity hash derived from run inputs.
 */
object ComplianceEvidenceBuilder {
    /**
     * Builds a [ComplianceEvidence] record from validation outputs and run metadata.
     *
     * @param findings All findings from the validation run.
     * @param configSnapshot Serialized config used for this run (for audit trail).
     * @param mrId Identifier of the merge request being validated.
     * @param timestamp ISO-8601 or epoch string for when validation ran.
     * @return Structured evidence record suitable for audit storage.
     */
    fun build(
        findings: List<Finding>,
        configSnapshot: String,
        mrId: String,
        timestamp: String,
    ): ComplianceEvidence {
        val checklist = buildChecklist(findings)
        val contentForHash = "$mrId|$timestamp|$configSnapshot|${findings.size}"
        return ComplianceEvidence(
            mrId = mrId,
            timestamp = timestamp,
            configSnapshot = configSnapshot,
            findings = findings.map {
                ComplianceFinding(
                    rule = it.rule,
                    severity = it.severity.name,
                    message = it.message,
                    passed = it.severity == Severity.INFO,
                )
            },
            checklist = checklist,
            integrityHash = sha256Hex(contentForHash),
        )
    }

    /**
     * Formats a [ComplianceEvidence] record as a Markdown table report.
     *
     * @param evidence The evidence pack to render.
     * @param language `report.language` of the fixed texts; null is English.
     * @return Markdown string with header and per-rule checklist table.
     */
    fun formatReport(
        evidence: ComplianceEvidence,
        language: String? = null,
    ): String =
        buildString {
            appendLine(AiReportText.COMPLIANCE_TITLE.of(language))
            appendLine(AiReportText.COMPLIANCE_MR.of(language, evidence.mrId, evidence.timestamp))
            appendLine(AiReportText.COMPLIANCE_INTEGRITY.of(language, evidence.integrityHash))
            appendLine()
            appendLine(AiReportText.COMPLIANCE_CHECKLIST.of(language))
            appendLine(AiReportText.COMPLIANCE_HEADER.of(language))
            appendLine("|------|--------|----------|-------------|")
            for (item in evidence.checklist) {
                val status = AiReportText.label(item.status, language)
                val severity = AiReportText.label(item.maxSeverity, language)
                appendLine("| ${item.rule} | $status | ${item.findingCount} | $severity |")
            }
        }

    private fun buildChecklist(findings: List<Finding>): List<ComplianceCheckItem> {
        val rules = findings.groupBy { it.rule }
        return rules
            .map { (rule, ruleFindings) ->
                val passed = ruleFindings.all { it.severity == Severity.INFO }
                ComplianceCheckItem(
                    rule = rule,
                    status = if (passed) STATUS_PASS else STATUS_FAIL,
                    findingCount = ruleFindings.size,
                    maxSeverity = ruleFindings.minOf { it.severity }.name,
                )
            }.sortedBy { it.status }
    }

    private const val STATUS_PASS = "PASS"
    private const val STATUS_FAIL = "FAIL"
}

/**
 * Full audit-grade evidence pack for a single validation run.
 *
 * @param mrId Merge request identifier.
 * @param timestamp When validation ran.
 * @param configSnapshot Config content captured at validation time.
 * @param findings Flat list of all findings with their compliance pass/fail status.
 * @param checklist Per-rule summary items, sorted by status.
 * @param integrityHash Unsigned hex hash of key run inputs for tamper detection.
 */
@Serializable
data class ComplianceEvidence(
    val mrId: String,
    val timestamp: String,
    val configSnapshot: String,
    val findings: List<ComplianceFinding>,
    val checklist: List<ComplianceCheckItem>,
    val integrityHash: String,
)

/**
 * Single finding entry in the compliance evidence pack.
 *
 * @param rule Rule that produced the finding.
 * @param severity Severity name (ERROR, WARNING, INFO).
 * @param message Finding message.
 * @param passed True when severity is INFO (rule considered passing).
 */
@Serializable
data class ComplianceFinding(
    val rule: String,
    val severity: String,
    val message: String,
    val passed: Boolean,
)

/**
 * Per-rule checklist item in a compliance report.
 *
 * @param rule Rule identifier.
 * @param status `PASS` if all findings are INFO, `FAIL` otherwise.
 * @param findingCount Total findings for this rule.
 * @param maxSeverity Name of the highest severity found for this rule.
 */
@Serializable
data class ComplianceCheckItem(
    val rule: String,
    val status: String,
    val findingCount: Int,
    val maxSeverity: String,
)
