package io.karandash.scripts

import io.karandash.api.PluginContext
import io.karandash.duration.KarandashDuration
import io.karandash.interpolation.KarandashEnvSecurity
import io.karandash.platform.ProcessTimeouts
import kotlinx.serialization.Serializable

/**
 * Parsed configuration for a single script entry from the `scripts:` block.
 *
 * @param path Path to the script file, relative to the working directory; it must resolve inside that directory.
 * @param mode Execution mode: sandbox (QuickJS), subprocess (Node.js), or auto-detect.
 * @param timeout Maximum execution time.
 * @param env Env var names the script may read; null (undeclared) keeps every non-denylisted var visible and warns
 *   about each one read. `KarandashEnvSecurity.DENYLIST` always wins over this list.
 */
@Serializable
data class ScriptEntryConfig(
    val path: String,
    val mode: ScriptMode = ScriptMode.AUTO,
    val timeout: KarandashDuration = DEFAULT_TIMEOUT,
    val env: List<String>? = null,
) {
    companion object {
        val DEFAULT_TIMEOUT: KarandashDuration = KarandashDuration.ofSeconds(ProcessTimeouts.DEFAULT_SECONDS.toLong())
    }
}

/** True when [name] is on `KarandashEnvSecurity.DENYLIST`, compared ignoring case (Windows and shells differ on case). */
internal fun isDenylistedEnv(name: String): Boolean = KarandashEnvSecurity.DENYLIST.any { it.equals(name, ignoreCase = true) }

private const val MAX_ENV_NAME_LENGTH = 128
private const val MAX_WARNED_ENV_NAMES = 50

/** Upper bound on distinct names kept by the in-process recorder; the warning shows [MAX_WARNED_ENV_NAMES]. */
internal const val MAX_RECORDED_ENV_NAMES: Int = 1000

private fun Char.isEnvNameStart(): Boolean = this == '_' || this in 'A'..'Z' || this in 'a'..'z'

/** `[A-Za-z_][A-Za-z0-9_]{0,127}`; anything else (control chars, ANSI, newlines) never reaches a log line. */
internal fun isValidEnvName(name: String): Boolean =
    name.length in 1..MAX_ENV_NAME_LENGTH && name[0].isEnvNameStart() && name.all { it.isEnvNameStart() || it in '0'..'9' }

/** [ScriptEntryConfig.env] minus `KarandashEnvSecurity.DENYLIST`, or null when the script declared no `env`. */
internal val ScriptEntryConfig.allowedEnv: List<String>?
    get() = env?.filterNot(::isDenylistedEnv)

/** One warning naming the valid, non-denylisted vars a script without `env` read; names only, never values. */
internal fun PluginContext.warnUndeclaredEnvReads(
    entry: ScriptEntryConfig,
    names: Collection<String>,
) {
    if (entry.env != null) return
    val undeclared = names.filter { isValidEnvName(it) && !isDenylistedEnv(it) }.distinct().sorted()
    if (undeclared.isEmpty()) return
    val shown = undeclared.take(MAX_WARNED_ENV_NAMES).joinToString(", ", "[", "]")
    val more = undeclared.size - MAX_WARNED_ENV_NAMES
    warn(
        "Script '${entry.path}' read undeclared env vars $shown${if (more > 0) " and $more more" else ""}; " +
            "declare them in scripts[].env — undeclared reads will be stripped in the next release",
    )
}

/**
 * Script execution mode.
 *
 * - [AUTO] - detect from file extension: `.mjs` → subprocess, everything else → sandbox.
 * - [SANDBOX] - embedded QuickJS engine, no filesystem/network access.
 * - [SUBPROCESS] - Node.js subprocess with full system access.
 */
enum class ScriptMode {
    AUTO,
    SANDBOX,
    SUBPROCESS;

    companion object {
        fun fromString(value: String): ScriptMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown script mode: '$value'. Expected: sandbox, subprocess, auto")
    }
}
