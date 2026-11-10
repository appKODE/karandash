package io.karandash.config

import io.karandash.duration.KarandashDuration
import io.karandash.platform.ProcessTimeouts
import kotlinx.serialization.Serializable

/**
 * Parsed configuration for a single script entry from the `scripts:` block.
 *
 * @param path Relative or absolute path to the script file.
 * @param mode Execution mode: sandbox (QuickJS), subprocess (Node.js), or auto-detect.
 * @param timeout Maximum execution time.
 * @param env Env var names the script may read (`scripts[].env`); null when the entry declares none.
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
