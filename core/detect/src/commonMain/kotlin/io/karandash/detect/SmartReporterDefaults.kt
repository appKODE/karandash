package io.karandash.detect

import io.karandash.api.ModuleRegistry
import io.karandash.api.module.ReporterModule
import io.karandash.api.platform.ProviderDetectionScore
import kotlinx.serialization.Serializable

/**
 * Infers the best default reporter based on detected CI environment.
 *
 * Selection logic:
 * 1. If a provider was detected (CI environment active), select the reporter with [ReporterModule.isDefaultCI].
 * 2. Otherwise, select the reporter with [ReporterModule.isDefaultInteractive].
 * 3. If neither flag is set, fall back to [ReporterModule.isDefaultReporter].
 *
 * @param registry Module registry containing registered reporter modules.
 */
class SmartReporterDefaults(
    private val registry: ModuleRegistry,
) {
    /**
     * Resolve the best default reporter format ID based on the detection result.
     *
     * @param detectionScore The provider detection score, or null if no provider was detected.
     * @return The format ID of the selected reporter, or null if no suitable reporter is found.
     */
    fun resolveDefaultFormat(detectionScore: ProviderDetectionScore?): String? = resolveDefaultFormat(isCI = detectionScore != null)

    /** Same as [resolveDefaultFormat] for callers that only know whether a CI environment is active. */
    fun resolveDefaultFormat(isCI: Boolean): String? {
        val reporters = registry.reporters()
        if (reporters.isEmpty()) return null

        return if (isCI) {
            resolveCIReporter(reporters) ?: resolveFallbackReporter(reporters)
        } else {
            resolveInteractiveReporter(reporters) ?: resolveFallbackReporter(reporters)
        }
    }

    /**
     * Returns a diagnostic summary of available reporter defaults.
     */
    fun diagnostics(detectionScore: ProviderDetectionScore?): ReporterDefaultsDiagnostics {
        val reporters = registry.reporters()
        return ReporterDefaultsDiagnostics(
            isCI = detectionScore != null,
            selectedFormat = resolveDefaultFormat(detectionScore),
            availableFormats = reporters.map { it.formatId },
            ciDefault = reporters.firstOrNull { it.isDefaultCI }?.formatId,
            interactiveDefault = reporters.firstOrNull { it.isDefaultInteractive }?.formatId,
            fallbackDefault = reporters.firstOrNull { it.isDefaultReporter }?.formatId,
        )
    }

    private fun resolveCIReporter(reporters: List<ReporterModule>): String? = reporters.firstOrNull { it.isDefaultCI }?.formatId

    private fun resolveInteractiveReporter(reporters: List<ReporterModule>): String? =
        reporters.firstOrNull { it.isDefaultInteractive }?.formatId

    private fun resolveFallbackReporter(reporters: List<ReporterModule>): String? = reporters.firstOrNull { it.isDefaultReporter }?.formatId
}

/**
 * Diagnostic information about reporter default resolution.
 *
 * @property isCI Whether a CI environment was detected.
 * @property selectedFormat The format ID that would be selected.
 * @property availableFormats All registered reporter format IDs.
 * @property ciDefault The CI-default reporter format ID, if any.
 * @property interactiveDefault The interactive-default reporter format ID, if any.
 * @property fallbackDefault The fallback-default reporter format ID, if any.
 */
@Serializable
data class ReporterDefaultsDiagnostics(
    val isCI: Boolean,
    val selectedFormat: String?,
    val availableFormats: List<String>,
    val ciDefault: String?,
    val interactiveDefault: String?,
    val fallbackDefault: String?,
)
