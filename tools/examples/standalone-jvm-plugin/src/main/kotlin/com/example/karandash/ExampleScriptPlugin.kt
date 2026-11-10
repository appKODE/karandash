package com.example.karandash

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.ScriptModule
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Standalone example Karandash script plugin.
 *
 * This plugin demonstrates:
 * - Implementing [ScriptModule] to execute custom scripts against MR data
 * - Returning INFO-level findings from script execution
 * - Proper ServiceLoader registration
 *
 * Build: ./gradlew build
 * Install: cp build/libs/standalone-jvm-plugin.jar ~/.karandash/plugins/
 */
class ExampleScriptPlugin : ScriptModule {
    override val id: String = "example-jvm-script"
    override val name: String = "Example JVM Script Plugin"
    override val version: String = "1.0.0"
    override val type: ModuleType get() = ModuleType.SCRIPT

    override suspend fun initialize(context: PluginContext): Boolean = true

    /**
     * Executes each provided script and returns one INFO finding per script
     * to confirm that execution was invoked.
     *
     * @param scripts List of script definitions as [JsonElement] entries.
     * @param mr The MR under validation.
     * @param context Execution context providing logging and env access.
     * @return One INFO finding per script confirming successful execution.
     */
    override suspend fun execute(
        scripts: List<JsonElement>,
        mr: MergeRequest,
        context: PluginContext,
    ): List<Finding> {
        if (scripts.isEmpty()) return emptyList()

        return scripts.mapIndexed { index, script ->
            val scriptId = (script as? JsonObject)
                ?.get("id")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: "script-${index + 1}"
            Finding(
                rule = "example-jvm-script",
                message = "Script '$scriptId' executed successfully",
                severity = Severity.INFO,
                location = null,
            )
        }
    }
}
