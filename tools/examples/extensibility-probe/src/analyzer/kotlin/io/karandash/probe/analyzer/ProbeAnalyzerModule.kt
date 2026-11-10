package io.karandash.probe.analyzer

import io.karandash.models.finding.Finding
import io.karandash.models.finding.PresentationKeys
import io.karandash.models.finding.Severity
import io.karandash.sdk.PluginContext
import io.karandash.sdk.ai.AiAnalyzerContext
import io.karandash.sdk.ai.AiAnalyzerModule
import io.karandash.sdk.finding.MetaKey
import io.karandash.sdk.finding.withMeta
import kotlinx.serialization.Serializable

/** Typed payload the probe attaches to its finding (typed keys replace stringly `metadata` keys). */
@Serializable
data class ProbeScore(
    val score: Int,
    val reason: String,
)

/**
 * Typed-meta-key probe: an analyzer that attaches a typed [MetaKey] value and routes its finding into its own
 * report section via [PresentationKeys.SECTION] - without editing core/models or any formatter.
 *
 * Red until the SDK adds `MetaKey` / `withMeta` (plugins/sdk `finding/MetaKey.kt`) and
 * `PresentationKeys` (core/models).
 */
class ProbeAnalyzerModule : AiAnalyzerModule {
    override val id: String = ID
    override val name: String = "Extensibility probe — analyzer"
    override val version: String = "0.0.0"
    override val analyzerId: String = ID

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        val finding =
            Finding(
                severity = Severity.INFO,
                message = "probe score for ${context.mr.title}",
                rule = ID,
                metadata = mapOf(PresentationKeys.SECTION to SECTION),
            )
        return listOf(finding.withMeta(SCORE_KEY, ProbeScore(score = 1, reason = "probe")))
    }

    companion object {
        const val ID = "probe-analyzer"
        const val SECTION = "probe"
        val SCORE_KEY: MetaKey<ProbeScore> = MetaKey("probe.score", ProbeScore.serializer())
    }
}
