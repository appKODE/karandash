package io.karandash.plugins.ai.core

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.config.ProcessRiskConfig
import io.karandash.plugins.ai.core.config.ProcessRiskFactors
import kotlinx.serialization.json.JsonObject

/**
 * the one shared risk computation of the triage cluster. `ai.processRisk`, `risk-scoring`, `pr-split`,
 * `change-categorization` and the `mr-summary` risks all measure the MR through [of], so they report the same
 * composite: the analyzers put it in their prompt ([promptSection]) and on their findings ([metadata]).
 */
object TriageRisk {
    /** The MR's process risk, counting the factors of an enabled `ai.processRisk` block (all factors otherwise). */
    fun of(
        mr: MergeRequest,
        config: ProcessRiskConfig?,
        history: MrHistory = MrHistory(),
    ): ProcessRiskMetrics =
        ProcessRiskCalculator.calculate(mr, history, factors = config?.takeIf { it.enabled }?.factors ?: ProcessRiskFactors())

    /** [of] for an analyzer: the `processRisk` block is read from the AI plugin config the analyzer receives. */
    fun of(
        context: AiAnalyzerContext,
        history: MrHistory = MrHistory(),
    ): ProcessRiskMetrics {
        val block = (context.config as? JsonObject)?.get(CONFIG_KEY)
        return of(context.mr, block?.let { KarandashJson.Default.decodeFromJsonElement(ProcessRiskConfig.serializer(), it) }, history)
    }

    /** [of] with the author's track record from the platform ([AuthorHistory]), as the analyzers and the pipeline measure it. */
    suspend fun measure(context: AiAnalyzerContext): ProcessRiskMetrics = of(context, AuthorHistory.load(context.mr, context.logger))

    /** The prompt section stating the measured risk, so the model weighs it instead of guessing its own. */
    fun promptSection(risk: ProcessRiskMetrics): String =
        buildString {
            appendLine("## Measured Process Risk")
            appendLine("${line(risk)} (0.0 = lowest, 1.0 = highest; computed from the change itself, use it as the baseline)")
            appendLine(
                "- change churn ${risk.churnRatio.formatDecimals(2)}, file diversity ${risk.fileDiversity.formatDecimals(2)}, " +
                    "change density ${risk.commitDensity.formatDecimals(2)}, " +
                    "author familiarity ${risk.authorFamiliarity.formatDecimals(2)}",
            )
        }

    /** One line naming the composite, as the `mr-summary` risks list it. */
    fun line(risk: ProcessRiskMetrics): String = "Measured process risk: ${risk.composite.formatDecimals(2)}"

    /** The finding metadata carrying the composite. */
    fun metadata(risk: ProcessRiskMetrics): Map<String, String> =
        mapOf(AiFindingMetadataKeys.PROCESS_RISK to risk.composite.formatDecimals(2))

    /** [findings] with the composite ([metadata]) added to each. */
    fun stamp(
        findings: List<Finding>,
        risk: ProcessRiskMetrics,
    ): List<Finding> = findings.map { it.copy(metadata = it.metadata + metadata(risk)) }

    private const val CONFIG_KEY = "processRisk"
}
