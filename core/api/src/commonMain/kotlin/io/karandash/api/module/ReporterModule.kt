package io.karandash.api.module

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.StableKarandashApi
import io.karandash.models.finding.Finding
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@StableKarandashApi
public interface ReporterModule : Module {
    override val type: ModuleType get() = ModuleType.REPORTER

    /**
     * The format ID that this reporter handles (e.g., "json", "webhook", "slack")
     */
    public val formatId: String

    /**
     * Config key for reporter-specific configuration from the reporters block.
     * Defaults to [formatId] to match DSL parser output keys.
     */
    override val configKey: String get() = formatId

    /**
     * Returns reporter-specific runtime configuration derived from the application config
     * and CLI parameters.
     *
     * Called by the CLI before [report] to inject settings from the config file and
     * command-line flags. Override to extract reporter-specific settings.
     *
     * @param reportersConfig The serialized `reporters` block from the application config as [JsonElement].
     * @param cliParams Runtime parameters from the CLI as [JsonElement], including: quiet, verbose, explain,
     *                  appVersion, and other format-specific flags.
     * @return A [JsonElement] of config keys to values. Default returns empty [JsonObject].
     */
    public suspend fun runtimeReporterConfig(
        reportersConfig: JsonElement,
        cliParams: JsonElement
    ): JsonElement = JsonObject(emptyMap())

    /**
     * Whether this reporter requires platform context (platform, project, mrId).
     * When true, skipped if platform context is unavailable. Same meaning as
     * [OutputModule.requiresPlatformContext]. Default: false.
     */
    @ExperimentalKarandashApi
    public val requiresPlatformContext: Boolean get() = false

    /**
     * Whether running this reporter changes something outside the process (posts comments,
     * calls webhooks). `dry-run-scope: all` skips reporters that return true.
     * Default: true, so an unknown third-party reporter is skipped (safe side); local-only reporters
     * (console, files) override it with false.
     */
    @ExperimentalKarandashApi
    public val hasExternalSideEffects: Boolean get() = true

    /**
     * Whether this reporter can use live mode (comment posting) when available,
     * but does not require it to function.
     *
     * When true, the reporter receives poster config when live mode is available,
     * but still works in non-live mode (e.g., returns formatted output).
     * Default: same as [requiresPlatformContext].
     */
    @OptIn(ExperimentalKarandashApi::class)
    public val supportsLiveMode: Boolean get() = requiresPlatformContext

    /**
     * Whether this reporter is the default when no `--format` is specified.
     * Exactly one reporter in the registry should return true (typically console).
     */
    public val isDefaultReporter: Boolean get() = false

    /**
     * Whether this reporter is the default for interactive (non-CI) use.
     *
     * When no `--format` is specified and the tool is not running in CI/live mode,
     * the reporter with this flag is selected. Typically the console reporter.
     */
    public val isDefaultInteractive: Boolean get() = false

    /**
     * Whether this reporter is the default for CI/live-mode use.
     *
     * When zero-config mode detects live mode (platform, project, mrId available),
     * the reporter with this flag replaces the interactive default. Typically the
     * comment reporter.
     */
    public val isDefaultCI: Boolean get() = false

    /**
     * Whether this reporter supports file output via `outputFile` config.
     * When true, the CLI may write the reporter's output to a file.
     */
    public val supportsFileOutput: Boolean get() = false

    /**
     * Report validation results.
     *
     * @param findings List of validation findings
     * @param passed Whether validation passed overall
     * @param config Reporter-specific configuration as [JsonElement]
     * @return Optional output string (for file-based reporters)
     */
    public suspend fun report(
        findings: List<Finding>,
        passed: Boolean,
        config: JsonElement
    ): String?
}
