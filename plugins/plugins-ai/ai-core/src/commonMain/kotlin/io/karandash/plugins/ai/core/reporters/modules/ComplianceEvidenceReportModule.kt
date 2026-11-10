package io.karandash.plugins.ai.core.reporters.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiReportModule
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.reporters.ComplianceEvidenceBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Report module wrapper for [ComplianceEvidenceBuilder].
 *
 * Produces a structured compliance evidence pack for SOC2/ISO27001 audits.
 */
class ComplianceEvidenceReportModule : AiReportModule {
    override val id: String get() = ID
    override val name: String get() = "Compliance Evidence"
    override val version: String get() = VERSION
    override val reportId: String get() = REPORT_ID
    override val reportName: String get() = "Compliance Evidence"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.FINDINGS, ReportInput.MERGE_REQUEST)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun generate(context: ReportContext): ReportResult {
        val evidence = ComplianceEvidenceBuilder.build(
            findings = context.findings,
            configSnapshot = context.config.toString(),
            mrId = context.mr.id,
            timestamp = "",
        )
        val formatted = ComplianceEvidenceBuilder.formatReport(evidence, LanguagePromptOverlays.responseLanguageOf(context.config))
        return ReportResult(
            reportId = REPORT_ID,
            data = JsonObject(
                mapOf(
                    "mrId" to JsonPrimitive(evidence.mrId),
                    "integrityHash" to JsonPrimitive(evidence.integrityHash),
                    "checklistSize" to JsonPrimitive(evidence.checklist.size),
                ),
            ),
            formatted = formatted,
        )
    }

    companion object {
        const val ID = "ai-report-compliance-evidence"
        const val REPORT_ID = "compliance-evidence"
        private const val VERSION = "1.0.0"
    }
}
