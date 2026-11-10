package io.karandash.plugins.ai.core.reporters.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiReportModule
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.reporters.DigestAudience
import io.karandash.plugins.ai.core.reporters.SmartDigestGenerator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Report module wrapper for [SmartDigestGenerator].
 *
 * Generates audience-tailored natural language summaries of validation findings.
 * Delegates to the existing [SmartDigestGenerator] with a developer audience
 * (no AI service in this context -- produces static summaries).
 */
class SmartDigestReportModule : AiReportModule {
    override val id: String get() = ID
    override val name: String get() = "Smart Digest"
    override val version: String get() = VERSION
    override val reportId: String get() = REPORT_ID
    override val reportName: String get() = "Smart Digest"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.FINDINGS)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun generate(context: ReportContext): ReportResult {
        val generator = SmartDigestGenerator(aiService = null, language = LanguagePromptOverlays.responseLanguageOf(context.config))
        val result = generator.generate(context.findings, DigestAudience.DEVELOPER)
        val stats = generator.computeStats(context.findings)
        return ReportResult(
            reportId = REPORT_ID,
            data = JsonObject(
                mapOf(
                    "audience" to JsonPrimitive(result.audience.name),
                    "aiEnhanced" to JsonPrimitive(result.aiEnhanced),
                    "totalFindings" to JsonPrimitive(stats.totalFindings),
                    "passRate" to JsonPrimitive(stats.passRate),
                ),
            ),
            formatted = result.summary,
            metrics = mapOf("pass-rate" to stats.passRate),
        )
    }

    companion object {
        const val ID = "ai-report-smart-digest"
        const val REPORT_ID = "smart-digest"
        private const val VERSION = "1.0.0"
    }
}
