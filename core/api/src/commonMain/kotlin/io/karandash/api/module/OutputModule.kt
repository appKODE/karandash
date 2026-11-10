package io.karandash.api.module

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.StableKarandashApi
import io.karandash.api.finding.FormattedReport
import io.karandash.models.DiffRefs
import kotlinx.serialization.json.JsonElement

/**
 * Transport/delivery module that sends a [FormattedReport] to a destination
 * (e.g. stdout, filesystem, platform MR comments, webhook, Slack, email).
 *
 * Output modules handle I/O and delivery - they receive pre-formatted content
 * from a [FormatModule] and write it to their target.
 *
 * ## Lifecycle
 *
 * Same as [Module]: [initialize] → [output] (0+ times) → [shutdown].
 *
 * @see FormatModule For producing formatted reports
 * @see FormattedReport The bridge data class between format and output
 */
@StableKarandashApi
public interface OutputModule : Module {
    override val type: ModuleType get() = ModuleType.OUTPUT

    /**
     * Unique identifier for this output destination (e.g. "stdout", "platform", "filesystem").
     */
    public val outputId: String

    override val configKey: String get() = outputId

    /**
     * Set of format IDs this output accepts. Empty set means all formats are accepted.
     *
     * For example, the platform output only accepts `{"markdown"}` because it posts
     * markdown comments to MR/PR platforms.
     */
    public val acceptedFormats: Set<String> get() = emptySet()

    /**
     * Whether this output requires platform context (platform + project + mrId).
     *
     * When `true`, the output is silently skipped if platform context is unavailable.
     */
    public val requiresPlatformContext: Boolean get() = false

    /** Whether this output can write to a file (`outputFile` config). Default: false. */
    @ExperimentalKarandashApi
    public val supportsFileOutput: Boolean get() = false

    /**
     * Format ID this output prefers when a `reporters:` entry names the output but no `format:`.
     * `null` (default) lets the host fall back to `json`.
     */
    @ExperimentalKarandashApi
    public val preferredFormat: String? get() = null

    /**
     * Receives the platform context before [output] when [requiresPlatformContext] is true.
     * Default: ignored.
     */
    @ExperimentalKarandashApi
    public fun configurePlatformContext(
        platformService: PlatformServiceModule,
        projectId: String,
        mrId: String,
        diffRefs: DiffRefs?,
        diffLineRanges: Map<String, Set<Int>>,
    ) {
    }

    /**
     * Whether running this output changes something outside the process (posts comments,
     * calls webhooks). `dry-run-scope: all` skips outputs that return true.
     * Default: true, so an unknown third-party output is skipped (safe side); local-only outputs
     * (console, files) override it with false.
     */
    @ExperimentalKarandashApi
    public val hasExternalSideEffects: Boolean get() = true

    /**
     * Whether this output is the default for interactive (non-CI) use.
     *
     * Exactly one output module should return `true` (typically stdout).
     */
    public val isDefaultInteractive: Boolean get() = false

    /**
     * Whether this output is the default when platform context is available.
     *
     * Exactly one output module should return `true` (typically platform).
     */
    public val isDefaultPlatform: Boolean get() = false

    /**
     * Deliver a [FormattedReport] to this output's destination.
     *
     * @param report The formatted report to deliver.
     * @param config Output-specific configuration as [JsonElement] (from the `config:` block).
     */
    public suspend fun output(
        report: FormattedReport,
        config: JsonElement,
    )
}
