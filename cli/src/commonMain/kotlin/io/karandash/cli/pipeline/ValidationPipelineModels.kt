package io.karandash.cli.pipeline

import io.karandash.api.ModuleRegistry
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.run.RunSession
import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.bootstrap.RunPluginContexts
import io.karandash.cli.commands.validate.FormatOutputPlanner
import io.karandash.cli.commands.validate.ResolvedFormatOutput
import io.karandash.config.Config
import io.karandash.content.AuthRegistry
import io.karandash.engine.ValidationEngine
import io.karandash.engine.ValidationResult
import io.karandash.models.MergeRequest
import io.karandash.sdk.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class CIContext(
    val platform: String?,
    val project: String?,
    val mrId: String?,
    val isCI: Boolean,
    val platformConfidence: String? = null,
    val platformSource: String? = null,
)

/** Not @Serializable: runtime context holder, holds a non-serializable `AuthRegistry`. */
internal data class ConfigLoadStage(
    val config: Config,
    val configFilePath: String?,
    val usingZeroConfig: Boolean,
    val registry: ModuleRegistry,
    val bootstrap: PluginBootstrap,
    /** This run's context settings; never shared with another run. */
    val contexts: RunPluginContexts = bootstrap.newRunContexts(),
    val rawElement: JsonElement? = null,
    val authRegistry: AuthRegistry = AuthRegistry.EMPTY,
    /** Extends-chain entries (source URL, parsed element), deepest ancestor first; empty for zero-config. */
    val configLayers: List<Pair<String, JsonElement>> = emptyList(),
    /** Local files of the resolved `extends` chain (remote entries left out); watch mode watches all of them. */
    val extendsChainFiles: List<String> = emptyList(),
    /** The run this config belongs to; modules initialized mid-run are tracked here so the run shuts them down. */
    val session: RunSession? = null,
    /** `${VAR}` references the config makes to variables that are neither set nor given an `env:` default. */
    val missingEnvVars: Set<String> = emptySet(),
) {
    /** the top-level config that extension hooks get, with the run's effective `ai:` block (dry run, show prompt). */
    val extensionConfig: JsonElement?
        get() {
            val ai = config.ai ?: return rawElement
            val root = rawElement as? JsonObject ?: return JsonObject(mapOf(AI_KEY to ai))
            return JsonObject(root + (AI_KEY to ai))
        }

    private companion object {
        const val AI_KEY = "ai"
    }
}

@Serializable
internal data class ProviderStage(
    val provider: PlatformServiceModule?,
    val mr: MergeRequest,
    /** Project/MR ids the MR was fetched by (`--pr`, auto-detect, live); preferred over CI env detection. */
    val projectId: String? = null,
    val mrId: String? = null,
)

/** Not @Serializable: runtime context holder, holds a non-serializable `ValidationEngine`. */
internal data class ValidationStage(
    val engine: ValidationEngine,
    val result: ValidationResult,
)

/** Not @Serializable: runtime context holder, holds a non-serializable `FormatOutputPlanner`. */
internal data class ReportingStage(
    val pairs: List<ResolvedFormatOutput>,
    val planner: FormatOutputPlanner,
    val output: String?,
)

@Serializable
internal data class FixStage(
    val finalResult: ValidationResult,
)

/**
 * Validation options extracted from CLI parameters.
 *
 * This data class decouples the pipeline from Clikt command infrastructure,
 * making it easier to test and reuse.
 *
 * Not @Serializable: runtime context holder, holds a non-serializable `PluginBootstrap`.
 */
data class ValidationOptions(
    val configPathStr: String?,
    val mrDataStr: String?,
    val mrId: String?,
    val platform: String?,
    val project: String?,
    val prUrl: String?,
    val format: String,
    /**
     * When true, the format was explicitly set via CLI flag.
     * Suppresses secondary reporters so only the requested format runs.
     */
    val explicitFormat: Boolean = false,
    /**
     * When true, renders the comment body to stdout instead of posting it to the MR.
     */
    val dryRunComment: Boolean = false,
    /** `--watch`: a [io.karandash.api.module.ReporterModule] with `supportsLiveMode == false` is not run. */
    val liveMode: Boolean = false,
    val outputPathStr: String?,
    /** `--baseline`: fingerprint file whose findings are not reported. */
    val baselinePath: String? = null,
    /** `--update-baseline`: rewrite [baselinePath] from the current findings instead of applying it. */
    val updateBaseline: Boolean = false,
    val quiet: Boolean,
    val verbose: Boolean,
    val strict: Boolean,
    val dryRun: Boolean,
    val explain: Boolean,
    /** `--style concise|full`: the console layout (and every human format's `report.style`); null keeps the config's. */
    val style: String? = null,
    /** ANSI colour in console output: a terminal, no `NO_COLOR`, `TERM` not `dumb` and no `--no-color`. */
    val color: Boolean = false,
    val fix: Boolean,
    val yes: Boolean,
    val noAutoDetect: Boolean,
    /** Writes primary program output (report results) to stdout. */
    val outputWriter: (String) -> Unit,
    /**
     * CLI override for missing-plugin behavior: "fail", "warn", or "ignore".
     * When null, the value from manifest.yaml is used (defaulting to WARN).
     */
    val onMissingPlugin: String? = null,
    /**
     * Pre-loaded [io.karandash.bootstrap.PluginBootstrap] to reuse across commands.
     * When provided, plugin loading is skipped and the shared registry is used directly.
     * The caller owns the lifecycle - shutdown is NOT called on this bootstrap by the pipeline.
     */
    val sharedBootstrap: PluginBootstrap,
    /**
     * When true, print configured rules grouped by execution priority and exit without validation.
     */
    val showExecutionPlan: Boolean = false,
    /**
     * `--dry-run --show-prompt`: run instead of stopping after config loading, with `dry-run-scope: all`,
     * AI dry run and `ai.debug.prompt: artifact`, so every AI prompt is built and written, none sent.
     */
    val showPrompt: Boolean = false,
    /**
     * Profile ID supplied via CLI `--profile` flag.
     * When non-null, overrides any profile reference in the config file.
     */
    val profileId: String? = null,
    /**
     * CLI override for Dangerfile path. When non-null, used instead of auto-detection
     * or the `dangerfile:` key in config.
     */
    val dangerfilePathOverride: String? = null,
    /**
     * When true, SSRF protection is relaxed: localhost, private IPs, and HTTP URLs
     * are permitted for providers and webhooks.
     */
    val allowLocalhost: Boolean = false,
    /**
     * When true, run the pre-validation enrichment stage (generate title/description via AI).
     * This is the CLI `--enrich` flag. Ignored when [io.karandash.config.EnrichmentConfig] is set via config file.
     */
    val enrich: Boolean = false,
    /**
     * When true, preview enrichment suggestions without applying them to the MR.
     * Maps to the CLI `--dry-run-enrich` flag.
     */
    val dryRunEnrich: Boolean = false,
    /**
     * When true, auto-approve the MR if findings are below the configured severity threshold.
     * Maps to the CLI `--auto-approve` flag.
     */
    val autoApprove: Boolean = false,
    /**
     * When true, disables staleness gate cache - all rules re-execute.
     */
    val noCache: Boolean = false,
    /**
     * When true, cached findings from unchanged files that appear in the report also count for the
     * exit code and auto-approve (CLI `--gate-on-cached`). Default false.
     */
    val gateOnCached: Boolean = false,
    /**
     * Comma-separated rule IDs to selectively invalidate from cache.
     */
    val invalidateRules: String? = null,
    /**
     * File path for JSON runtime diagnostics (memory/CPU snapshots, timeline).
     * When non-null, a [io.karandash.diagnostics.DiagnosticCollector] runs in
     * the background and periodically flushes data to this file.
     * CLI `--diagnostic <path>` overrides `diagnostics.output` in config.
     */
    val diagnosticPath: String? = null,
    /**
     * Directory for `--dump-dir`: every layer input and output of the run is saved there as a file
     * ([DumpDirWriter]). Null (the default) dumps nothing and serializes nothing.
     */
    val dumpDir: String? = null,
    /**
     * Pre-fetched merge request (watch mode). When non-null, provider resolution and the fetch
     * are skipped and this MR goes through the rest of the pipeline as fetched.
     */
    val mrOverride: MergeRequest? = null,
    /**
     * Rule IDs to run (`validate-local --rules`); every other configured rule is dropped after the config
     * and profile are resolved. Matched separator- and case-insensitively. Null runs every configured rule.
     */
    val onlyRules: Set<String>? = null,
    /** `config validate --base`: a missing local `{ file: }` warns (consumers provide it) instead of failing. */
    val missingFilesWarn: Boolean = false,
    /** Skip the unknown-key warnings: `plugin install/update` read only `cache.downloads`, so the rest is not theirs to judge. */
    val skipUnknownKeyWarnings: Boolean = false,
    /**
     * Whether loading the config also applies its run settings: the AI service, `timeouts.pluginShutdown` and the
     * `timeouts.global` watchdog. Off for commands that only read the config ([configOnly]).
     */
    val applyRunSettings: Boolean = true,
    /**
     * Logger for operational messages throughout the pipeline.
     * Controls verbosity via quiet/verbose settings.
     */
    val logger: Logger = CliLogger(),
) {
    companion object {
        /**
         * Options for commands that only read the config (everything but `validate`): no MR, quiet, no auto-detect,
         * no run settings applied.
         */
        internal fun configOnly(
            configPath: String?,
            sharedBootstrap: PluginBootstrap,
            missingFilesWarn: Boolean = false,
            strict: Boolean = false,
        ) = ValidationOptions(
            configPathStr = configPath,
            mrDataStr = null,
            mrId = null,
            platform = null,
            project = null,
            prUrl = null,
            format = "json",
            outputPathStr = null,
            quiet = true,
            verbose = false,
            strict = strict,
            dryRun = false,
            explain = false,
            fix = false,
            yes = false,
            noAutoDetect = true,
            outputWriter = {},
            sharedBootstrap = sharedBootstrap,
            missingFilesWarn = missingFilesWarn,
            applyRunSettings = false,
        )
    }
}

/**
 * Final result from the validation pipeline.
 *
 * Contains all artifacts needed by CLI to handle exit codes, watch mode,
 * and output generation.
 */
@Serializable
data class ValidationPipelineResult(
    val exitCode: Int,
    val config: Config,
    val configFilePath: String?,
    val usingZeroConfig: Boolean,
    val mr: MergeRequest?,
    val validationResult: ValidationResult?,
    val reportOutput: String?,
    val extendsChainFiles: List<String> = emptyList(),
)
