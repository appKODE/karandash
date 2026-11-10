package io.karandash.api.module

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.StableKarandashApi
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import kotlinx.serialization.json.JsonElement

/**
 * Pure data-transformation module that converts a [ValidationReport] into a
 * [FormattedReport] in a specific output format (e.g. markdown, JSON, SARIF).
 *
 * Format modules are stateless transformers - they must not perform I/O or
 * side effects. Delivery is handled by [OutputModule].
 *
 * ## Lifecycle
 *
 * Same as [Module]: [initialize] → [format] (0+ times) → [shutdown].
 *
 * @see OutputModule For delivering formatted reports to destinations
 * @see FormattedReport The bridge data class between format and output
 */
@StableKarandashApi
public interface FormatModule : Module {
    override val type: ModuleType get() = ModuleType.FORMAT

    /**
     * Unique identifier for the output format (e.g. "markdown", "json", "sarif").
     *
     * Used by the pipeline to match `--format` CLI flag and config `format:` keys.
     */
    public val formatId: String

    override val configKey: String get() = formatId

    /**
     * Whether this format is the default for interactive (non-CI) use.
     *
     * Exactly one format module should return `true` (typically console).
     */
    public val isDefaultInteractive: Boolean get() = false

    /**
     * Whether this format is the default when platform context is available.
     *
     * Exactly one format module should return `true` (typically markdown).
     */
    public val isDefaultPlatform: Boolean get() = false

    /**
     * Transform a [ValidationReport] into a [FormattedReport].
     *
     * This method must be a pure transformation - no I/O, no side effects.
     *
     * @param report The fully-enriched validation report.
     * @param config Format-specific configuration as [JsonElement].
     * @return The formatted report ready for delivery by an [OutputModule].
     */
    public suspend fun format(
        report: ValidationReport,
        config: JsonElement,
    ): FormattedReport
}
