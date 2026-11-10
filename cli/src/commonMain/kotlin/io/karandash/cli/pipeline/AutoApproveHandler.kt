package io.karandash.cli.pipeline

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.warnUnsupported
import io.karandash.api.support.rethrowCancellation
import io.karandash.cli.commands.validate.DynamicModuleSelector
import io.karandash.cli.commands.validate.ValidateCommandScope
import io.karandash.config.AutoApproveConfig
import io.karandash.models.finding.Severity
import io.karandash.sdk.Logger
import kotlinx.serialization.json.JsonElement

/**
 * Handles auto-approve/unapprove logic based on validation findings severity.
 *
 * Extracted from [ValidationPipeline] to isolate approval logic
 * from the main orchestration flow.
 *
 * @lifecycle per-run
 */
internal class AutoApproveHandler(
    private val logger: Logger,
    private val autoApproveFlag: Boolean,
    private val dryRun: Boolean,
    private val runtimeModuleInitializer: suspend (ConfigLoadStage, Module, JsonElement) -> Unit,
    private val resolveCIContext: suspend (scope: ValidateCommandScope) -> CIContext,
    private val resolveModuleConfig: (ConfigLoadStage, Module, String?) -> JsonElement,
) {
    @Suppress("TooGenericExceptionCaught") // boundary: approval/unapproval may fail
    suspend fun applyAutoApprove(
        configStage: ConfigLoadStage,
        scope: ValidateCommandScope,
        providerStage: ProviderStage,
        validationStage: ValidationStage,
    ) {
        val configAutoApprove = configStage.config.autoApprove
        val effectiveConfig = when {
            autoApproveFlag -> {
                configAutoApprove ?: AutoApproveConfig()
            }

            configAutoApprove?.enabled == true -> {
                configAutoApprove
            }

            else -> {
                logger.debug("Auto-approve: not enabled (no --auto-approve flag and config not enabled)")
                return
            }
        }
        if (dryRun) {
            logger.debug("Auto-approve: skipped in dry-run mode")
            return
        }

        val maxSeverity = Severity.fromString(effectiveConfig.maxSeverity, Severity.INFO)
        val findings = validationStage.result.findings
        val worstSeverity = findings.minByOrNull { it.severity.ordinal }?.severity
        val shouldApprove = worstSeverity == null || worstSeverity.ordinal >= maxSeverity.ordinal

        val ci = resolveCIContext(scope)
        val effectiveProject = providerStage.projectId ?: ci.project ?: run {
            logger.debug("Auto-approve: skipped — CI project ID not available")
            return
        }
        val effectiveMrId = providerStage.mrId ?: ci.mrId ?: run {
            logger.debug("Auto-approve: skipped — CI MR ID not available")
            return
        }

        val mutator = providerStage.provider
            ?: DynamicModuleSelector.resolve<PlatformServiceModule>(
                registry = scope.registry,
                type = ModuleType.PLATFORM_SERVICE,
                platformName = ci.platform,
                preferredPluginIds = scope.preferredPluginIds,
            )
        if (mutator == null) {
            logger.warn("Auto-approve skipped: no mutator available")
            return
        }

        val pluginConfig = resolveModuleConfig(configStage, mutator, providerStage.provider?.configKey)
        runtimeModuleInitializer(configStage, mutator, pluginConfig)

        if (shouldApprove) {
            val approved = try {
                mutator.approve(effectiveProject, effectiveMrId)
                logger.info("MR auto-approved (worst severity: ${worstSeverity?.name ?: "none"})")
                true
            } catch (_: UnsupportedOperationException) {
                logger.warnUnsupported(mutator.name, "approve")
                false
            } catch (e: Exception) {
                // boundary: platform service approve() may throw any exception
                e.rethrowCancellation()
                logger.warn("Auto-approve failed: ${e.message}")
                false
            }
            if (approved && effectiveConfig.merge) merge(mutator, effectiveProject, effectiveMrId)
        } else {
            try {
                mutator.unapprove(effectiveProject, effectiveMrId)
                logger.info(
                    "MR auto-unapproved: ${worstSeverity.name} finding(s) exceed threshold ${maxSeverity.name}",
                )
            } catch (_: UnsupportedOperationException) {
                logger.warnUnsupported(mutator.name, "unapprove")
            } catch (e: Exception) {
                // boundary: platform service unapprove() may throw any exception
                e.rethrowCancellation()
                logger.warn("Auto-unapprove failed: ${e.message}")
            }
        }
    }

    private suspend fun merge(
        mutator: PlatformServiceModule,
        projectId: String,
        mrId: String,
    ) {
        mutator
            .mergeMr(projectId, mrId)
            .onSuccess { logger.info("MR auto-merged") }
            .onFailure { logger.warn("Auto-merge failed: ${it.message}") }
    }
}
