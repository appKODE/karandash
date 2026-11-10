@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.commands.validate

import com.github.ajalt.clikt.core.ProgramResult
import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.errors.ProviderException
import io.karandash.api.module.FixDismissalAware
import io.karandash.api.module.FixHandler
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.FetchOptions
import io.karandash.cli.errors.ExitCodes
import io.karandash.engine.ValidationEngine
import io.karandash.engine.ValidationResult
import io.karandash.fixing.AutoFixer
import io.karandash.sdk.Logger
import kotlinx.serialization.Serializable

/**
 * Result of applying auto-fixes, including the re-validation result
 * and optional report output for the caller to display.
 */
@Serializable
internal data class FixFlowResult(
    val validationResult: ValidationResult,
    val reportOutput: String? = null,
)

/**
 * Encapsulates validate --fix flow (analysis, confirmation, mutation, re-validation).
 *
 * @param dryRun report the planned fixes and apply none (`--dry-run` or `dry-run-scope: all`).
 */
internal class ValidateFixFlow(
    private val yes: Boolean,
    private val dryRun: Boolean = false,
    private val effectivePlatform: String?,
    private val effectiveProject: String,
    private val effectiveMrId: String,
    private val isCiEnvironment: () -> Boolean,
    private val logger: Logger,
    private val runtimeModuleInitializer: suspend (Module) -> Unit,
    private val fetchOptions: FetchOptions = FetchOptions(),
) {
    suspend fun applyAutoFixes(
        result: ValidationResult,
        provider: PlatformServiceModule?,
        engine: ValidationEngine,
        pairs: List<ResolvedFormatOutput>,
        planner: FormatOutputPlanner,
        registry: ModuleRegistry,
        preferredPluginIds: Set<String> = emptySet(),
    ): FixFlowResult {
        val mutator = provider
            ?: DynamicModuleSelector.resolve<PlatformServiceModule>(
                registry = registry,
                type = ModuleType.PLATFORM_SERVICE,
                platformName = effectivePlatform,
                preferredPluginIds = preferredPluginIds
            )
        if (mutator == null) {
            logger.error("")
            logger.error("--fix requires live mode with a provider that supports mutations (GitLab, GitHub).")
            logger.error("File mode (--mr) does not support auto-fix.")
            throw ProgramResult(ExitCodes.PROVIDER_ERROR)
        }
        runtimeModuleInitializer(mutator)

        val fixHandlers = registry.allModules().filterIsInstance<FixHandler>().distinct()
        val fixer = AutoFixer(mutator, effectiveProject, effectiveMrId, fixHandlers = fixHandlers)
        val fixableActions = fixer.analyzeFixable(result.findings)

        if (fixableActions.isEmpty()) {
            logger.info("")
            logger.info("No auto-fixable issues found. Remaining issues require manual intervention.")
            return FixFlowResult(result)
        }

        logger.info("")
        logger.info("Auto-fixable issues found (${fixableActions.size}):")
        fixableActions.forEach { action ->
            logger.info("  - ${action.description}")
        }

        if (dryRun) {
            logger.info("dry-run: ${fixableActions.size} fix(es) planned, none applied.")
            return FixFlowResult(result)
        }

        if (!yes) {
            if (isCiEnvironment()) {
                logger.error("")
                logger.error("--fix requires --yes flag in CI/non-interactive environments")
                throw ProgramResult(ExitCodes.CONFIG_ERROR)
            }

            logger.info("")
            logger.info("Apply these fixes? [y/N] ")
            val response = readlnOrNull()?.trim()?.lowercase()
            if (response != "y" && response != "yes") {
                logger.info("Fixes not applied.")
                return FixFlowResult(result)
            }
        }

        val fixResults = fixer.applyFixes(fixableActions)
        val succeeded = fixResults.count { it.success }
        val skipped = fixResults.count { it.skipped }
        val failed = fixResults.count { !it.success && !it.skipped }

        logger.info("")
        fixResults.forEach { fixResult ->
            val status = when {
                fixResult.success -> "OK"
                fixResult.skipped -> "SKIPPED"
                else -> "FAILED"
            }
            val errorSuffix = if (fixResult.error != null) " (${fixResult.error})" else ""
            logger.info("  [$status] ${fixResult.action.description}$errorSuffix")
        }
        logger.info("")
        val skippedSuffix = if (skipped > 0) ", $skipped skipped" else ""
        logger.info("Applied $succeeded fix(es)${if (failed > 0) ", $failed failed" else ""}$skippedSuffix.")

        val successfulFindings = fixResults
            .filter { it.success }
            .map { it.action.finding }
        if (successfulFindings.isNotEmpty()) {
            val dismissalHandlers = registry
                .getByType(ModuleType.RULE)
                .filterIsInstance<FixDismissalAware>()
            for (handler in dismissalHandlers) {
                for (finding in successfulFindings) {
                    handler.onFindingFixed(finding)
                }
            }
        }

        if (failed > 0) {
            throw ProgramResult(ExitCodes.VALIDATION_FAILED)
        }

        logger.info("")
        logger.info("Re-validating...")

        val liveProvider = provider
            ?: throw ProviderException("Auto-fix requires a merge request provider for re-validation")

        val updatedMr = liveProvider.fetchMergeRequest(effectiveProject, effectiveMrId, fetchOptions)
        val revalidationResult = engine.validate(updatedMr)

        val explanations = io.karandash.bootstrap.ModuleDefaultsCollector
            .ruleExplanations(registry)
        val revalidationReport = io.karandash.engine.ReportDataBuilder.build(
            result = revalidationResult,
            explanations = explanations,
        )
        val revalidationOutput = planner.runPairs(pairs, revalidationReport)

        return FixFlowResult(revalidationResult, revalidationOutput)
    }
}
