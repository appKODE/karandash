package io.karandash.scripts

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.ScriptModule
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.platform.readText
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemPathSeparator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Built-in implementation of [ScriptModule].
 *
 * Routes each script entry to either [SandboxScriptExecutor] (QuickJS) or
 * [SubprocessScriptExecutor] (Node.js) based on the resolved execution mode.
 */
class BuiltInScriptModule : ScriptModule {
    override val id: String = "io.karandash.script"
    override val name: String = "Script"
    override val version: String = "1.7.0"
    override val type: ModuleType = ModuleType.SCRIPT

    override val description: String = "Execute user-authored validation scripts (sandbox or subprocess)"

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun execute(
        scripts: List<JsonElement>,
        mr: MergeRequest,
        context: PluginContext,
    ): List<Finding> = scripts.flatMap { raw -> runEntry(raw, mr, context) }

    /** One entry, parsed and run on its own: a bad entry or an unreadable script is a warning, not the end of the others. */
    private suspend fun runEntry(
        raw: JsonElement,
        mr: MergeRequest,
        context: PluginContext,
    ): List<Finding> =
        try {
            executeEntry(ScriptConfigParser.parse(listOf(raw)).single(), mr, context)
        } catch (ignored: Exception) {
            // boundary: one user script entry (config, file read, executor) must not drop the findings of the others
            ignored.rethrowCancellation()
            val label = ((raw as? JsonObject)?.get("path") as? JsonPrimitive)?.content ?: raw.toString()
            context.warn("Script '$label' skipped: ${ignored.message}")
            emptyList()
        }

    private suspend fun executeEntry(
        entry: ScriptEntryConfig,
        mr: MergeRequest,
        context: PluginContext,
    ): List<Finding> {
        val scriptPath = Path(context.workingDirectory, entry.path)
        if (!SystemFileSystem.exists(scriptPath)) {
            context.warn("Script file not found: ${entry.path}")
            return emptyList()
        }
        if (!isInsideWorkingDirectory(scriptPath, context.workingDirectory)) {
            context.warn("Script path '${entry.path}' is outside the working directory")
            return emptyList()
        }

        val source = scriptPath.readText()

        return when (resolveMode(entry)) {
            ScriptMode.SANDBOX -> SandboxScriptExecutor(context).run(entry, mr, source)

            ScriptMode.SUBPROCESS -> SubprocessScriptExecutor(context).run(
                entry,
                mr,
                source,
                context.workingDirectory
            )

            ScriptMode.AUTO -> error("Mode should be resolved before execution")
        }
    }

    /** Canonical (`..`- and symlink-resolved) [scriptPath] must stay under [workingDirectory]. */
    private fun isInsideWorkingDirectory(
        scriptPath: Path,
        workingDirectory: String,
    ): Boolean {
        val root = SystemFileSystem.resolve(Path(workingDirectory)).toString().trimEnd(SystemPathSeparator)
        return SystemFileSystem.resolve(scriptPath).toString().startsWith("$root$SystemPathSeparator")
    }

    /**
     * Resolve [ScriptMode.AUTO] to a concrete mode.
     *
     * - `.mjs` files → subprocess (ES module syntax needs Node.js)
     * - All other `.js` files → sandbox (QuickJS)
     */
    private fun resolveMode(entry: ScriptEntryConfig): ScriptMode =
        when (entry.mode) {
            ScriptMode.AUTO -> if (entry.path.endsWith(".mjs")) {
                ScriptMode.SUBPROCESS
            } else {
                ScriptMode.SANDBOX
            }

            else -> entry.mode
        }
}
