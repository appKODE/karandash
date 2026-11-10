package io.karandash.cli.pipeline

import io.karandash.api.support.rethrowCancellation
import io.karandash.cli.dangerfile.DangerfileDetector
import io.karandash.cli.dangerfile.DangerfileRunner
import io.karandash.config.TimeoutConfig
import io.karandash.models.MergeRequest
import io.karandash.platform.safeParent
import io.karandash.sdk.Logger
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Runs configured scripts and Dangerfiles, merging their findings
 * into the validation result.
 *
 * Extracted from [ValidationPipeline] to isolate script execution
 * from the main orchestration flow.
 *
 * @lifecycle per-run
 */
internal class ScriptExecutor(
    private val logger: Logger,
    private val runtimeModuleInitializer: suspend (
        ConfigLoadStage,
        io.karandash.api.Module,
        kotlinx.serialization.json.JsonElement
    ) -> Unit,
) {
    /**
     * Run configured scripts from the `scripts:` config block.
     * Script findings are merged into the validation result.
     */
    suspend fun runScriptsIfConfigured(
        configStage: ConfigLoadStage,
        mr: MergeRequest,
        validationStage: ValidationStage,
    ): ValidationStage {
        val scripts = configStage.config.scripts
        if (scripts.isNullOrEmpty()) {
            logger.debug("Scripts: none configured, skipped")
            return validationStage
        }

        val scriptModule = configStage.registry.scriptModules().firstOrNull()
        if (scriptModule == null) {
            logger.warn("scripts: block configured but no ScriptModule registered")
            return validationStage
        }
        logger.debug("Scripts: running through module '${scriptModule.id}'")

        logger.info("Running ${scripts.size} configured script(s)")

        runtimeModuleInitializer(configStage, scriptModule, JsonObject(emptyMap()))
        val context = configStage.contexts.forModule(scriptModule.id)

        val scriptElements = scripts.map { s ->
            JsonObject(
                buildMap {
                    put("path", JsonPrimitive(s.path))
                    put("mode", JsonPrimitive(s.mode.value))
                    put("timeout", JsonPrimitive(s.timeout.toString()))
                    s.env?.let { env -> put("env", JsonArray(env.map(::JsonPrimitive))) }
                },
            )
        }

        @Suppress("TooGenericExceptionCaught") // boundary: script execution
        val scriptFindings = try {
            scriptModule.execute(scriptElements, mr, context)
        } catch (e: Exception) {
            // boundary: script execution
            e.rethrowCancellation()
            logger.warn("Script execution failed: ${e.message}")
            emptyList()
        }

        logger.debug("Scripts: ${scriptFindings.size} finding(s)")
        if (scriptFindings.isEmpty()) return validationStage

        val allFindings = validationStage.result.findings + scriptFindings
        val augmentedResult = validationStage.result.copy(findings = allFindings)
        return ValidationStage(validationStage.engine, augmentedResult)
    }

    /**
     * Run a Dangerfile if one is detected or configured.
     * Dangerfile findings are merged into the validation result.
     */
    suspend fun runDangerfileIfConfigured(
        configStage: ConfigLoadStage,
        mr: MergeRequest,
        validationStage: ValidationStage,
        dangerfilePathOverride: String?,
        configPathStr: String?,
    ): ValidationStage {
        val dangerfilePath = dangerfilePathOverride ?: configStage.config.dangerfile
        val workingDir = configPathStr?.let { Path(it).safeParent?.toString() } ?: "."

        val detected = DangerfileDetector().detect(workingDir, dangerfilePath)
        if (detected == null) {
            logger.debug("Dangerfile: none found in '$workingDir' (configured path: ${dangerfilePath ?: "none"})")
            return validationStage
        }

        logger.info("Running Dangerfile: ${detected.absolutePath}")

        @Suppress("TooGenericExceptionCaught") // boundary: Dangerfile runner is a plugin boundary
        val dangerFindings = try {
            val timeout = (configStage.config.timeouts?.dangerfile ?: TimeoutConfig.DEFAULT_DANGERFILE).toKotlinDuration()
            DangerfileRunner(workingDir, logger, timeout).run(detected, mr)
        } catch (e: Exception) {
            // boundary: Dangerfile runner executes user scripts
            e.rethrowCancellation()
            logger.warn("Dangerfile execution failed: ${e.message}")
            emptyList()
        }

        logger.debug("Dangerfile: ${dangerFindings.size} finding(s)")
        if (dangerFindings.isEmpty()) return validationStage

        val allFindings = validationStage.result.findings + dangerFindings
        val augmentedResult = validationStage.result.copy(findings = allFindings)
        return ValidationStage(validationStage.engine, augmentedResult)
    }
}
