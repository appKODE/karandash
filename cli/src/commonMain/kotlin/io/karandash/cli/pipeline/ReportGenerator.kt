@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.pipeline

import io.karandash.api.Module
import io.karandash.api.finding.BaselineComparison
import io.karandash.api.finding.TimingData
import io.karandash.api.module.ModuleScope
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.run.RunSession
import io.karandash.cli.commands.validate.FormatOutputPlanner
import io.karandash.cli.commands.validate.ReporterModuleFormat
import io.karandash.cli.commands.validate.ResolvedFormatOutput
import io.karandash.cli.commands.validate.toConfigured
import io.karandash.config.keys.ModuleIds
import io.karandash.models.MergeRequest
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.sdk.Logger
import io.karandash.utils.mr.HunkParser
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.measureTimedValue

/**
 * Generates validation reports by selecting format+output pairs and running them.
 *
 * Replaces the old reporter dispatch with the format/output separation:
 * 1. Creates a [FormatOutputPlanner] from the current context
 * 2. Selects format+output pairs
 * 3. Initializes all format and output modules
 * 4. Builds a [io.karandash.api.finding.ValidationReport] via [io.karandash.engine.ReportDataBuilder]
 * 5. Runs pairs via [FormatOutputPlanner.runPairs]
 * 6. Handles file output for `--output` flag
 *
 * Extracted from [ValidationPipeline] to isolate reporting logic.
 *
 * @lifecycle per-run
 */
internal class ReportGenerator(
    private val logger: Logger,
    private val options: ValidationOptions,
    private val runtimeModuleInitializer: suspend (ConfigLoadStage, Module, JsonElement) -> Unit,
    private val resolveModuleConfig: (ConfigLoadStage, Map<String, JsonElement>?, Module, String?) -> JsonElement,
    private val resolveCIContext: suspend (scope: io.karandash.cli.commands.validate.ValidateCommandScope) -> CIContext,
) {
    suspend fun generateReports(
        configStage: ConfigLoadStage,
        scope: io.karandash.cli.commands.validate.ValidateCommandScope,
        validationStage: ValidationStage,
        mr: MergeRequest? = null,
        providerConfigKey: String? = null,
        moduleEnabledOverrides: Map<String, Boolean> = emptyMap(),
        timingData: TimingData? = null,
        diag: PipelineDiagnosticBridge? = null,
        changedFiles: Set<String>? = null,
        session: RunSession? = null,
        baselineComparison: BaselineComparison? = null,
    ): ReportingStage {
        val ci = resolveCIContext(scope)
        val hasPlatformContext = ci.platform != null && ci.platform != ModuleIds.LOCAL_GIT && ci.project != null && ci.mrId != null

        val planner = FormatOutputPlanner(
            format = if (options.explicitFormat) options.format else null,
            explicitFormat = options.explicitFormat,
            outputTarget = options.outputPathStr,
            hasPlatformContext = hasPlatformContext,
            logger = logger,
            formatFlagOverrides = formatFlagOverrides(options, mr, validationStage.result.findings),
            liveMode = options.liveMode,
        )

        val allPairs = planner.selectPairs(configStage.config, scope.registry)
        logger.debug("Reporter pairs selected: ${allPairs.size} total")
        allPairs.forEach {
            logger.debug(
                "[output] select ${it.formatModule.formatId}: ${it.outputModule.outputId} (explicitFormat=${options.explicitFormat})"
            )
        }

        val enabledPairs = if (moduleEnabledOverrides.isEmpty()) {
            allPairs
        } else {
            // policy `modules:` entries are camelCased at parse time: `sarif-file` arrives as `sarifFile`
            fun disabledByPolicy(module: Module) =
                setOf(module.id, module.configKey).any {
                    moduleEnabledOverrides[it] == false || moduleEnabledOverrides[ConfigKeyNormalizer.kebabToCamelCase(it)] == false
                }
            allPairs.filter { pair ->
                val formatDisabled = disabledByPolicy(pair.formatModule)
                val outputDisabled = disabledByPolicy(pair.outputModule)
                if (formatDisabled || outputDisabled) {
                    logger.debug(
                        "Reporter pair ${pair.formatModule.id}+${pair.outputModule.id}: skipped by policy",
                    )
                }
                !formatDisabled && !outputDisabled
            }
        }

        val pairs = withRunHandles(
            printInsteadOfPosting(skipSideEffectsInDryRun(enabledPairs, configStage.config), scope),
            configStage,
            session,
        )
        initializeFormatOutputModules(configStage, pairs)

        val platform = injectPlatformContext(
            pairs = pairs,
            scope = scope,
            configStage = configStage,
            providerConfigKey = providerConfigKey,
            mr = mr,
            ci = ci,
        )

        val explanations = io.karandash.bootstrap.ModuleDefaultsCollector
            .ruleExplanations(scope.registry)
        val validationReport = io.karandash.engine.ReportDataBuilder.build(
            result = validationStage.result,
            explanations = explanations,
            timingData = timingData,
            changedFiles = changedFiles,
            baselineComparison = baselineComparison,
        )

        val delivered = NotificationGate(logger).apply(pairs, validationReport, platform, ci.project, ci.mrId)
        val reportOutput = if (diag != null) {
            runPairsWithDiagnostics(planner, delivered, validationReport, diag)
        } else {
            planner.runPairs(delivered, validationReport)
        }

        return ReportingStage(
            pairs = pairs,
            planner = planner,
            output = reportOutput,
        )
    }

    private suspend fun runPairsWithDiagnostics(
        planner: FormatOutputPlanner,
        pairs: List<ResolvedFormatOutput>,
        report: io.karandash.api.finding.ValidationReport,
        diag: PipelineDiagnosticBridge,
    ): String? {
        val (result, duration) = measureTimedValue {
            planner.runPairs(pairs, report) { pair, formatMs, outputMs ->
                diag.recordReporterResult(
                    pair = pair.toConfigured(),
                    formatMs = formatMs,
                    outputMs = outputMs,
                )
            }
        }
        logger.debug("Reporter pairs (${pairs.size}): ${duration.inWholeMilliseconds}ms total")
        return result
    }

    /**
     * Swaps each [ModuleScope.PER_RUN] output for this run's own initialized handle, so per-run
     * state (the platform posting target) never leaks between overlapping runs. A pair whose
     * handle failed to initialize is dropped (the session already warned).
     */
    private suspend fun withRunHandles(
        pairs: List<ResolvedFormatOutput>,
        configStage: ConfigLoadStage,
        session: RunSession?,
    ): List<ResolvedFormatOutput> {
        if (session == null) return pairs
        return pairs.mapNotNull { pair ->
            val output = pair.outputModule
            if (output.scope != ModuleScope.PER_RUN) return@mapNotNull pair
            val ctx = configStage.contexts.contextWithConfig(output.id, pair.outputConfig)
            session.handle(output, ctx)?.let {
                ResolvedFormatOutput(pair.formatModule, it, pair.formatConfig, pair.outputConfig)
            }
        }
    }

    private suspend fun initializeFormatOutputModules(
        configStage: ConfigLoadStage,
        pairs: List<ResolvedFormatOutput>,
    ) {
        val initializedIds = mutableSetOf<String>()
        for (pair in pairs) {
            if (initializedIds.add(pair.formatModule.id)) {
                runtimeModuleInitializer(configStage, pair.formatModule, pair.formatConfig)
            }
            // a PER_RUN handle was initialized by the session in withRunHandles
            if (pair.outputModule.scope != ModuleScope.PER_RUN && initializedIds.add(pair.outputModule.id)) {
                runtimeModuleInitializer(configStage, pair.outputModule, pair.outputConfig)
            }
        }
    }

    /** `dry-run-scope: all`: nothing leaves the process, so outputs with external side effects are skipped. */
    private fun skipSideEffectsInDryRun(
        pairs: List<ResolvedFormatOutput>,
        config: io.karandash.config.Config,
    ): List<ResolvedFormatOutput> {
        if (config.activeDryRunScope != io.karandash.config.DryRunScope.ALL) return pairs
        // a reporter module has its side effects inside report(), so its own flag decides
        val (skipped, kept) = pairs.partition { it.reporterModule()?.hasExternalSideEffects ?: it.outputModule.hasExternalSideEffects }
        skipped.map { it.reporterModule()?.id ?: it.outputModule.outputId }.distinct().forEach { logger.info("dry-run: skipped $it") }
        return kept.map { it.withoutDebugArtifacts() }
    }

    /** A dry run writes no `review-flow.md`: the markdown format gets an empty `flowArtifactDir`. */
    private fun ResolvedFormatOutput.withoutDebugArtifacts(): ResolvedFormatOutput {
        val cfg = formatConfig as? JsonObject ?: return this
        if (formatModule.formatId != ModuleIds.MARKDOWN_FORMAT) return this
        val cleared = JsonObject(cfg + (ModuleIds.FLOW_ARTIFACT_DIR_KEY to JsonPrimitive("")))
        return ResolvedFormatOutput(formatModule, outputModule, cleared, outputConfig)
    }

    private fun ResolvedFormatOutput.reporterModule() = (formatModule as? ReporterModuleFormat)?.reporter

    /**
     * `--dry-run-comment`: pairs that would post to the MR print their body to the interactive output instead.
     */
    private fun printInsteadOfPosting(
        pairs: List<ResolvedFormatOutput>,
        scope: io.karandash.cli.commands.validate.ValidateCommandScope,
    ): List<ResolvedFormatOutput> {
        if (!options.dryRunComment) return pairs
        val console = scope.registry.outputs().firstOrNull { it.isDefaultInteractive } ?: return pairs
        return pairs.map { pair ->
            if (!pair.outputModule.requiresPlatformContext) return@map pair
            logger.debug("--dry-run-comment: '${pair.outputModule.outputId}' body goes to '${console.outputId}'")
            ResolvedFormatOutput(pair.formatModule, console, pair.formatConfig, JsonObject(emptyMap()))
        }
    }

    /**
     * For any output requiring platform context in the selected pairs, inject the platform context
     * by resolving the platform service and setting well-known metadata fields on the
     * output config so the module can post comments.
     *
     * @return The platform the outputs post through, or null when none was injected.
     */
    private suspend fun injectPlatformContext(
        pairs: List<ResolvedFormatOutput>,
        scope: io.karandash.cli.commands.validate.ValidateCommandScope,
        configStage: ConfigLoadStage,
        providerConfigKey: String?,
        mr: MergeRequest?,
        ci: CIContext,
    ): PlatformServiceModule? {
        val hasPlatformOutput = pairs.any { it.outputModule.requiresPlatformContext }
        if (!hasPlatformOutput) {
            logger.debug("Platform context injection: no platform-context output in selected pairs")
            return null
        }

        if (options.dryRunComment) {
            logger.debug("Comment posting: dry-run mode, skipping platform context injection")
            return null
        }

        val platform = resolvePlatformForPosting(
            ci = ci,
            configStage = configStage,
            scope = scope,
            providerConfigKey = providerConfigKey,
        ) ?: run {
            logger.debug("Platform context injection: could not resolve platform service")
            return null
        }

        val projectId = ci.project ?: run {
            logger.debug("Platform context injection: CI project ID not available")
            return null
        }
        val mrId = ci.mrId ?: run {
            logger.debug("Platform context injection: CI MR ID not available")
            return null
        }

        val diffLineRanges = diffLineRanges(mr)

        for (pair in pairs) {
            val outputModule = pair.outputModule
            if (outputModule.requiresPlatformContext) {
                outputModule.configurePlatformContext(
                    platformService = platform,
                    projectId = projectId,
                    mrId = mrId,
                    diffRefs = mr?.diffRefs,
                    diffLineRanges = diffLineRanges,
                )
            }
        }
        return platform
    }

    private suspend fun resolvePlatformForPosting(
        ci: CIContext,
        configStage: ConfigLoadStage,
        scope: io.karandash.cli.commands.validate.ValidateCommandScope,
        providerConfigKey: String?,
    ): PlatformServiceModule? {
        val platformName = ci.platform ?: run {
            logger.debug("Platform posting: no platform name in CI context")
            return null
        }

        val candidates = scope.registry.platformServices().filter { provider ->
            provider.id.contains(platformName, ignoreCase = true) ||
                provider.configKey == providerConfigKey
        }
        if (candidates.isEmpty()) {
            logger.debug("Platform posting: no matching platform service for '$platformName'")
            return null
        }

        val platform = candidates.first()

        val platformConfig = resolveModuleConfig(
            configStage,
            configStage.config.platforms,
            platform,
            providerConfigKey,
        )
        if (platformConfig == JsonObject(emptyMap())) {
            logger.debug(
                "Platform '${platform.id}': initializing with empty config (credentials expected from env vars)",
            )
        }
        runtimeModuleInitializer(configStage, platform, platformConfig)
        return platform
    }
}

/**
 * The new-file lines an inline comment may target, per changed file. A deletions-only file keeps its empty set:
 * dropping it made every line look valid and GitLab rejected the comment with 400 `line_code`. A file without diff
 * text (too large, not fetched) stays out of the map, so its lines are not checked.
 */
internal fun diffLineRanges(mr: MergeRequest?): Map<String, Set<Int>> =
    mr
        ?.changes
        .orEmpty()
        .filter { it.diffContent != null }
        .associate { change -> change.path to HunkParser.extractAddedLineNumbers(change.diffContent) }
