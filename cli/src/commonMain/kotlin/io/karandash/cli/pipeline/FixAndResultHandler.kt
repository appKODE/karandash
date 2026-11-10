package io.karandash.cli.pipeline

import io.karandash.api.Module
import io.karandash.api.errors.ConfigException
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.FetchOptions
import io.karandash.cli.commands.validate.ValidateCommandScope
import io.karandash.cli.commands.validate.ValidateFixFlow
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.Config
import io.karandash.config.DryRunScope
import io.karandash.detect.CIDetector
import io.karandash.engine.ValidationResult
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.normalize.ModuleIdNormalizer
import io.karandash.platform.readText
import io.karandash.sdk.Logger
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Handles auto-fix application, result computation, and exit code determination.
 *
 * Extracted from [ValidationPipeline] to isolate post-validation logic.
 *
 * @lifecycle per-run
 */
internal class FixAndResultHandler(
    private val options: ValidationOptions,
    private val logger: Logger,
    private val moduleInitializer: ModuleInitializer,
) {
    suspend fun applyFixes(
        configStage: ConfigLoadStage,
        scope: ValidateCommandScope,
        providerStage: ProviderStage,
        validationStage: ValidationStage,
        reportingStage: ReportingStage,
        fetchOptions: FetchOptions = FetchOptions(),
    ): FixStage {
        val result = validationStage.result
        // A passing run still gets fixes when warnings carry fix suggestions (they were skipped).
        if (!options.fix || (result.passed && result.findings.none { it.fixSuggestions.isNotEmpty() })) {
            return FixStage(result)
        }

        val fixResult = createFixFlow(
            providers = scope.registry.platformServices(),
            providerStage = providerStage,
            fetchOptions = fetchOptions,
            dryRun = options.dryRun || configStage.config.activeDryRunScope == DryRunScope.ALL,
            runtimeModuleInitializer = { module ->
                val pluginConfig = resolveModuleConfig(
                    configStage.config,
                    configStage.config.platforms,
                    module,
                    providerStage.provider?.configKey,
                )
                moduleInitializer.initializeRuntimeModule(configStage, module, pluginConfig)
            },
        ).applyAutoFixes(
            result = validationStage.result,
            provider = providerStage.provider,
            engine = validationStage.engine,
            pairs = reportingStage.pairs,
            planner = reportingStage.planner,
            registry = scope.registry,
            preferredPluginIds = scope.preferredPluginIds,
        )

        if (fixResult.reportOutput != null) {
            options.outputWriter(fixResult.reportOutput)
        }

        return FixStage(fixResult.validationResult)
    }

    fun computeResult(
        configStage: ConfigLoadStage,
        mr: MergeRequest,
        fixStage: FixStage,
        reportingStage: ReportingStage,
        gatedCachedFindings: List<Finding> = emptyList(),
    ): ValidationPipelineResult {
        val final = fixStage.finalResult
        val computed = computeExitCode(final.copy(findings = final.findings + gatedCachedFindings))
        // a report that could not be written must not look like a pass; a real failure code stays.
        val exitCode = if (computed == ExitCodes.SUCCESS && reportingStage.planner.outputFailed) ExitCodes.EXECUTION_ERROR else computed

        return ValidationPipelineResult(
            exitCode = exitCode,
            config = configStage.config,
            configFilePath = configStage.configFilePath,
            usingZeroConfig = configStage.usingZeroConfig,
            mr = mr,
            validationResult = fixStage.finalResult,
            reportOutput = reportingStage.output,
            extendsChainFiles = configStage.extendsChainFiles,
        )
    }

    fun printExecutionPlan(config: Config) {
        val configuredRuleIds = config.rules.ruleConfigs.keys
        val rows = configuredRuleIds
            .map { ruleId ->
                val meta = config.rules.metaFor(ruleId)
                Triple(meta.priority, ruleId, meta.bailOnFail)
            }.sortedWith(compareBy({ it.first }, { it.second }))

        val count = rows.size
        val groupCount = rows.map { it.first }.toSet().size
        logger.info("Execution Plan: $count rule(s), $groupCount priority group(s)")
        logger.info("Priority  Rule ID                          Bail-on-fail")
        logger.info("--------  --------------------------------  ------------")
        for ((priority, ruleId, bailOnFail) in rows) {
            val priorityStr = priority.toString().padEnd(8)
            val ruleIdStr = ruleId.padEnd(34)
            logger.info("$priorityStr  $ruleIdStr  ${if (bailOnFail) "yes" else "no"}")
        }
    }

    suspend fun resolveCIContext(providers: List<PlatformServiceModule>): CIContext {
        val detection = CIDetector(providers, logger).detect()
        return CIContext(
            platform = options.platform ?: detection?.platform,
            project = options.project ?: detection?.projectId,
            mrId = options.mrId ?: detection?.mrId,
            isCI = detection != null,
            // CIDetector only matches via CI env vars - HIGH confidence when found.
            platformConfidence = if (detection != null) "HIGH" else null,
            platformSource = if (detection != null) "ci:${detection.platform}" else null,
        )
    }

    fun resolveModuleConfig(
        config: Config,
        moduleConfigMap: Map<String, JsonElement>?,
        module: Module,
        providerFallbackKey: String?,
    ): JsonElement =
        moduleConfigMap?.let { blocks -> ModuleIdNormalizer.resolve(module.configKey, blocks.keys)?.let(blocks::get) }
            ?: config.extensions[module.configKey]
            ?: providerFallbackKey?.let(config::platformConfig)
            ?: JsonObject(emptyMap())

    fun readMrFileContent(path: String): String {
        val mrPath = Path(path)
        if (!SystemFileSystem.exists(mrPath) ||
            SystemFileSystem.metadataOrNull(mrPath)?.isRegularFile != true
        ) {
            throw ConfigException("MR data file not found: $path")
        }
        return mrPath.readText()
    }

    private fun computeExitCode(result: ValidationResult): Int =
        when {
            !result.passed -> {
                ExitCodes.VALIDATION_FAILED
            }

            options.strict && result.findings.any { it.severity == Severity.WARNING } -> {
                ExitCodes.VALIDATION_WARNING
            }

            else -> {
                ExitCodes.SUCCESS
            }
        }

    private suspend fun createFixFlow(
        providers: List<PlatformServiceModule>,
        providerStage: ProviderStage,
        fetchOptions: FetchOptions = FetchOptions(),
        dryRun: Boolean,
        runtimeModuleInitializer: suspend (Module) -> Unit,
    ): ValidateFixFlow {
        val ci = resolveCIContext(providers)
        return ValidateFixFlow(
            yes = options.yes,
            dryRun = dryRun,
            effectivePlatform = ci.platform,
            effectiveProject = providerStage.projectId ?: ci.project ?: "",
            effectiveMrId = providerStage.mrId ?: ci.mrId ?: "",
            isCiEnvironment = { ci.isCI },
            logger = logger,
            runtimeModuleInitializer = runtimeModuleInitializer,
            fetchOptions = fetchOptions,
        )
    }
}
