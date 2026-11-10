package io.karandash.scripts

import io.karandash.api.PluginContext
import io.karandash.api.support.rethrowCancellation
import io.karandash.interpolation.KarandashEnvSecurity
import io.karandash.js.JsUtils
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.platform.InspectionDenial
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.PlatformProcess
import io.karandash.platform.ProcessResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.measureTimedValue

/**
 * Executes scripts via Node.js subprocess.
 *
 * Writes a self-contained bridge script that sets up Karandash/Danger globals,
 * evaluates the user script in a VM context, and prints findings JSON to stdout after [RESULT_MARKER].
 * Variables in [KarandashEnvSecurity.DENYLIST] are removed from node's environment at exec time (so they are not in
 * its `/proc/self/environ` either), and on Linux this process is made non-dumpable first so the script cannot read
 * them back from `/proc/<karandash-pid>/environ` or `mem`. A script with a declared [ScriptEntryConfig.env] starts with
 * only those names in its environment (every other var is unset at exec too, see [execUnsetEnv]); one without it gets
 * a `process.env` proxy that records the names it reads, returned with the findings and warned about after the run. Threat model and residuals: `docs/configuration/scripts.md`.
 */
class SubprocessScriptExecutor(
    private val context: PluginContext,
    private val runProcess: suspend (
        command: List<String>,
        workingDir: String,
        timeoutSeconds: Int?,
        unsetEnv: Set<String>,
    ) -> ProcessResult =
        { command, workingDir, timeoutSeconds, unsetEnv ->
            PlatformProcess.executeAsync(command, workingDir, timeoutSeconds = timeoutSeconds, unsetEnv = unsetEnv)
        },
    private val denyInspection: () -> InspectionDenial = PlatformProcess::denySameUserInspection,
) {
    private val json = KarandashJson.Wire

    /**
     * Run a single script in subprocess mode via Node.js.
     *
     * @param entry Script configuration (path, timeout).
     * @param mr The merge request to validate.
     * @param source Pre-read script source code.
     * @param workingDir Working directory for process execution.
     * @return List of findings produced by the script.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun run(
        entry: ScriptEntryConfig,
        mr: MergeRequest,
        source: String,
        workingDir: String,
    ): List<Finding> {
        // each node run spends the run's subprocess budget (PluginSubprocessAccess); a spent budget skips the script.
        val budget = context.subprocessBudget
        if (budget.remainingCalls == 0 || budget.remainingWallMillis == 0L) {
            context.warn(
                "Script '${entry.path}' skipped: the run's subprocess budget " +
                    "(${budget.maxCalls} calls, ${budget.maxWallMillis} ms) is spent"
            )
            return emptyList()
        }
        val mrJson = json.encodeToString(MergeRequest.serializer(), mr)
        val bridgeScript = buildBridgeScript(entry, mrJson, source)

        return try {
            val timeoutSeconds = ((entry.timeout.millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt().coerceAtLeast(1)
            if (denyInspection() == InspectionDenial.Failed) {
                context.warn(
                    "Script '${entry.path}': could not make Karandash non-dumpable (prctl failed), so the script may read " +
                        "this process's environment and memory through /proc as a same-user process"
                )
            }
            val unsetEnv = execUnsetEnv(entry, PlatformEnvironment.names())
            val command = listOf("node", "-e", bridgeScript)
            val (result, took) = measureTimedValue { runProcess(command, workingDir, timeoutSeconds, unsetEnv) }
            budget.charge(command, took.inWholeMilliseconds)
            if (result.exitCode != 0) {
                context.warn(
                    "Script '${entry.path}' subprocess exited with code ${result.exitCode}: ${result.error}"
                )
                emptyList()
            } else {
                parseResults(entry, result.output)
            }
        } catch (e: Exception) {
            // boundary: user script subprocess and its output
            e.rethrowCancellation()
            context.warn("Script '${entry.path}' subprocess failed: ${e.message}")
            emptyList()
        }
    }

    private fun buildBridgeScript(
        entry: ScriptEntryConfig,
        mrJson: String,
        source: String,
    ): String {
        val escapedSource = escapeForJsString(source)
        val escapedPath = escapeForJsString(entry.path)

        return buildString {
            // Second layer: the vars are already unset at exec on every OS.
            appendLine("const denied = new Set(${jsArray(KarandashEnvSecurity.DENYLIST)});")
            appendLine("for (const k of Object.keys(process.env)) if (denied.has(k.toUpperCase())) delete process.env[k];")
            appendLine("const allowedEnv = ${entry.allowedEnv?.let(::jsArray) ?: "null"};")
            appendLine("if (allowedEnv) for (const k of Object.keys(process.env)) if (!allowedEnv.includes(k)) delete process.env[k];")
            appendLine("const vm = require('vm');")
            appendLine("const findings = [];")
            appendLine("const mr = $mrJson;")
            appendLine("function report(sev, msg, file, line, fix) {")
            appendLine("  findings.push({ severity: sev, message: String(msg), file: file || null, line: line || null,")
            appendLine("    fix: fix ? String(fix) : null });")
            appendLine("}")
            appendLine("const sandbox = {")
            appendLine("  karandash: {")
            appendLine("    mr: mr,")
            appendLine("    fail: (msg, file, line, fix) => report('error', msg, file, line, fix),")
            appendLine("    warn: (msg, file, line, fix) => report('warning', msg, file, line, fix),")
            appendLine("    message: (msg, file, line, fix) => report('info', msg, file, line, fix),")
            appendLine("    log: (msg) => process.stderr.write(String(msg) + '\\n'),")
            appendLine("    env: (name) => process.env[name] || null,")
            appendLine("  },")
            appendLine("  danger: { github: { pr: mr }, gitlab: { mr: mr }, git: {} },")
            appendLine("  warn: (msg, file, line, fix) => report('warning', msg, file, line, fix),")
            appendLine("  fail: (msg, file, line, fix) => report('error', msg, file, line, fix),")
            appendLine("  message: (msg, file, line, fix) => report('info', msg, file, line, fix),")
            appendLine("  markdown: (msg) => report('info', msg),")
            appendLine("  console: console, require: require, process: process,")
            appendLine("};")
            appendLine("let userSource = \"$escapedSource\";")
            appendLine("userSource = userSource.replace(/^\\s*import\\s+.*from\\s+['\"]danger['\"].*$/gm, '');")
            appendLine("vm.createContext(sandbox);")
            appendEnvReadRecorder()
            appendLine("try {")
            appendLine("  vm.runInContext(userSource, sandbox, { filename: \"$escapedPath\" });")
            appendLine("} catch (e) {")
            appendLine("  report('error', 'Script error: ' + e.message);")
            appendLine("}")
            appendLine("const result = { findings: findings, envReads: [...envReads] };")
            appendLine("process.stdout.write('\\n$RESULT_MARKER' + JSON.stringify(result));")
        }
    }

    /** Undeclared `env` only: `in`, property reads and enumeration of `process.env` record var names, never values. */
    private fun StringBuilder.appendEnvReadRecorder() {
        appendLine("const envReads = new Set();")
        appendLine("const noteRead = (t, k) => {")
        appendLine("  if (typeof k === 'string' && (Object.hasOwn(t, k) || /^[A-Z_][A-Z0-9_]*$/.test(k))) envReads.add(k);")
        appendLine("};")
        appendLine("if (!allowedEnv) process.env = new Proxy(process.env, {")
        appendLine("  get: (t, k) => { noteRead(t, k); return t[k]; },")
        appendLine("  has: (t, k) => { noteRead(t, k); return k in t; },")
        appendLine("  ownKeys: (t) => { Object.keys(t).forEach((k) => envReads.add(k)); return Reflect.ownKeys(t); },")
        appendLine("  getOwnPropertyDescriptor: (t, k) => { noteRead(t, k); return Reflect.getOwnPropertyDescriptor(t, k); },")
        appendLine("});")
    }

    private fun jsArray(names: Collection<String>): String = names.joinToString(",", "[", "]") { "\"${escapeForJsString(it)}\"" }

    @Suppress("TooGenericExceptionCaught")
    private fun parseResults(
        entry: ScriptEntryConfig,
        output: String,
    ): List<Finding> {
        if (RESULT_MARKER !in output) {
            context.warn("Script '${entry.path}' produced no result payload")
            return emptyList()
        }
        return try {
            val payload = json.parseToJsonElement(output.substringAfterLast(RESULT_MARKER)).jsonObject
            val envReads = payload["envReads"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            context.warnUndeclaredEnvReads(entry, envReads)
            checkNotNull(payload["findings"]) { "no findings array" }.jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val severity = when (obj["severity"]?.jsonPrimitive?.contentOrNull) {
                    "error" -> Severity.ERROR
                    "warning" -> Severity.WARNING
                    "info" -> Severity.INFO
                    else -> Severity.WARNING
                }
                val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val file = obj["file"]?.jsonPrimitive?.contentOrNull
                val line = obj["line"]?.jsonPrimitive?.content?.toIntOrNull()
                Finding(
                    severity = severity,
                    message = message,
                    rule = "script",
                    suggestion = obj["fix"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    location = if (file != null) {
                        Location.FileRef(path = file, line = line)
                    } else {
                        null
                    },
                )
            }
        } catch (e: Exception) {
            // boundary: user script subprocess and its output
            e.rethrowCancellation()
            context.warn("Failed to parse script results: ${e.message}")
            emptyList()
        }
    }

    private fun escapeForJsString(value: String): String = JsUtils.escapeForJs(value)

    companion object {
        /** Precedes the findings JSON on the bridge's stdout, so console output from the script cannot corrupt it. */
        const val RESULT_MARKER: String = "__KARANDASH_SCRIPT_RESULT__"

        private const val MILLIS_PER_SECOND = 1000L

        /**
         * Vars to unset when starting node: the denylist, plus, when [entry] declares `env`, every name in [present]
         * (this process's environment) that it does not declare, so node's initial environment holds only declared vars.
         */
        internal fun execUnsetEnv(
            entry: ScriptEntryConfig,
            present: Set<String>,
        ): Set<String> =
            KarandashEnvSecurity.DENYLIST + present.filter(::isDenylistedEnv) +
                entry.allowedEnv?.let { present - it.toSet() }.orEmpty()
    }
}
