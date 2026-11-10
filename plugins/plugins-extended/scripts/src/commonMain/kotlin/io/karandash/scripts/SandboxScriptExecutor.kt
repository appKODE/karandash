package io.karandash.scripts

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsInterruptedException
import io.karandash.api.PluginContext
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import kotlinx.coroutines.Dispatchers

/**
 * Executes scripts in a sandboxed QuickJS engine.
 *
 * Each script runs in its own isolated QuickJS instance with no filesystem
 * or network access. The Karandash DSL is injected to provide MR data and
 * finding reporting.
 */
class SandboxScriptExecutor(
    private val context: PluginContext
) {
    /**
     * Run a single script in sandbox mode.
     *
     * The engine's native interrupt enforces [ScriptEntryConfig.timeout], so busy JavaScript
     * (e.g. an infinite loop) is stopped and the engine released. The DSL has no async bindings,
     * so this JS-time deadline bounds the whole evaluation. A timed-out script keeps the findings
     * it reported before the deadline and is warned about.
     *
     * @param entry Script configuration (path, timeout).
     * @param mr The merge request to validate.
     * @param source Pre-read script source code.
     * @return List of findings produced by the script.
     */
    @Suppress("TooGenericExceptionCaught") // boundary: script execution
    suspend fun run(
        entry: ScriptEntryConfig,
        mr: MergeRequest,
        source: String,
    ): List<Finding> {
        val findings = mutableListOf<Finding>()
        val envReads = mutableSetOf<String>()
        val engine = QuickJs.create(Dispatchers.Default)
        try {
            engine.evaluationTimeoutMillis = entry.timeout.millis
            KarandashDslInjector.inject(engine, mr, findings, context, entry.allowedEnv, envReads)
            engine.evaluate<Any?>(KarandashDslInjector.stripDangerImports(source))
        } catch (e: QuickJsInterruptedException) {
            context.warn("Script '${entry.path}' timed out after ${entry.timeout.millis}ms: ${e.message}")
        } catch (e: Exception) {
            // boundary: user script
            e.rethrowCancellation()
            context.warn("Script '${entry.path}' failed: ${e.message}")
        } finally {
            engine.close()
        }
        context.warnUndeclaredEnvReads(entry, envReads)
        return findings.toList()
    }
}
