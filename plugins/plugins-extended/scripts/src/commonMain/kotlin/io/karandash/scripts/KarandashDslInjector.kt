package io.karandash.scripts

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import io.karandash.api.PluginContext
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Injects the Karandash scripting DSL into a QuickJS engine instance.
 *
 * Provides scripts with:
 * - `karandash.mr` - full MergeRequest as a JS object, plus `changedFiles` (paths) and summed `additions`/`deletions`
 * - `karandash.fail(msg, file?, line?, fix?)` → Finding(ERROR); `fix` becomes `Finding.suggestion`
 * - `karandash.warn(msg, file?, line?, fix?)` → Finding(WARNING)
 * - `karandash.message(msg, file?, line?, fix?)` → Finding(INFO)
 * - `karandash.log(msg)` → context.log()
 * - `karandash.env(name)` → context.env(): only `allowedEnv` names when declared; otherwise any name, recorded in `envReads`
 * - Danger compat: `danger` (with `danger.git` modified/created/deleted files and commits), `warn()`, `fail()`,
 *   `message()` (same arguments as above), `markdown()` globals
 */
object KarandashDslInjector {
    private val json = KarandashJson.Wire

    /**
     * Inject the Karandash DSL into [engine].
     *
     * Host functions are single-arg (QuickJS-kt constraint). Multi-arg DSL methods
     * are implemented in JS, serializing to JSON before calling the host function.
     */
    suspend fun inject(
        engine: QuickJs,
        mr: MergeRequest,
        findings: MutableList<Finding>,
        context: PluginContext,
        allowedEnv: List<String>?,
        envReads: MutableSet<String>,
    ) {
        val mrJson = JsonObject(
            json.encodeToJsonElement(MergeRequest.serializer(), mr).jsonObject +
                mapOf(
                    "changedFiles" to JsonArray(mr.changes.map { JsonPrimitive(it.path) }),
                    "additions" to JsonPrimitive(mr.changes.sumOf { it.additions }),
                    "deletions" to JsonPrimitive(mr.changes.sumOf { it.deletions }),
                ),
        ).toString()

        engine.function<String, Unit>("__karandash_log") { msg ->
            context.log(msg)
        }
        engine.function<String, String?>("__karandash_env") { name ->
            when {
                isDenylistedEnv(name) -> null

                allowedEnv == null -> context.env(name).also {
                    if (isValidEnvName(name) && envReads.size < MAX_RECORDED_ENV_NAMES) envReads.add(name)
                }

                name in allowedEnv -> context.env(name)

                else -> null
            }
        }
        engine.function<String, Unit>("__karandash_finding") { findingJson ->
            parseFinding(findingJson)?.let { findings.add(it) }
        }

        // Any?: the setup script's completion value is the last assigned function, not undefined.
        engine.evaluate<Any?>(
            buildString {
                appendLine("var karandash = {};")
                appendLine("karandash.mr = $mrJson;")
                append(
                    """
                    function __reportFinding(severity, msg, file, line, fix) {
                        __karandash_finding(JSON.stringify({
                            severity: severity,
                            message: String(msg),
                            file: file || null,
                            line: (typeof line === 'number') ? line : null,
                            fix: fix ? String(fix) : null
                        }));
                    }
                    karandash.fail = function(msg, file, line, fix) { __reportFinding('error', msg, file, line, fix); };
                    karandash.warn = function(msg, file, line, fix) { __reportFinding('warning', msg, file, line, fix); };
                    karandash.message = function(msg, file, line, fix) { __reportFinding('info', msg, file, line, fix); };
                    karandash.log = function(msg) { __karandash_log(String(msg)); };
                    karandash.env = function(name) { return __karandash_env(String(name)); };
                    """.trimIndent()
                )
                appendLine()
                appendLine("var danger = { github: { pr: karandash.mr }, gitlab: { mr: karandash.mr }, git: $DANGER_GIT };")
                appendLine("var warn = karandash.warn, fail = karandash.fail, message = karandash.message;")
                appendLine("function markdown(msg) { __reportFinding('info', msg); }")
            }
        )
    }

    /** Danger's `danger.git` (renames count as modified, as in Danger JS), built from `karandash.mr`. */
    private val DANGER_GIT =
        """
        (function(mr) {
            function paths(types) {
                return mr.changes
                    .filter(function(c) { return types.indexOf(c.changeType) >= 0; })
                    .map(function(c) { return c.path; });
            }
            return {
                modified_files: paths(['MODIFIED', 'RENAMED']),
                created_files: paths(['ADDED']),
                deleted_files: paths(['DELETED']),
                commits: mr.commits.map(function(c) {
                    var author = { name: c.author.name, email: c.author.email, date: c.timestamp };
                    return { sha: c.sha, message: c.message, author: author, committer: author };
                })
            };
        })(karandash.mr)
        """.trimIndent()

    /**
     * Strip `import ... from "danger"` lines from script source.
     */
    fun stripDangerImports(source: String): String =
        source
            .lineSequence()
            .filter { line ->
                val trimmed = line.trim()
                !trimmed.startsWith("import ") ||
                    (!trimmed.contains("\"danger\"") && !trimmed.contains("'danger'"))
            }.joinToString("\n")

    private fun parseFinding(findingJson: String): Finding? =
        try {
            val obj = json.parseToJsonElement(findingJson).jsonObject
            val severity = when (obj["severity"]?.jsonPrimitive?.contentOrNull) {
                "error" -> Severity.ERROR
                "warning" -> Severity.WARNING
                "info" -> Severity.INFO
                else -> Severity.WARNING
            }
            val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: return null
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
        } catch (ignored: Exception) {
            // boundary: output of a user script
            ignored.rethrowCancellation()
            null
        }
}
