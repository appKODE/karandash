package io.karandash.ai.pipeline

import io.karandash.platform.PlatformEnvironment
import kotlinx.atomicfu.atomic
import kotlin.time.Clock

/**
 * Whether bulky AI dumps (full responses, the per-file diff list of every rule) reach the debug log.
 *
 * On with the CLI `--trace` flag ([enable]) or `KARANDASH_AI_TRACE=1`; `--verbose` alone keeps one summary line.
 * Application-scoped: the flag is atomic.
 */
object AiTrace {
    const val ENV = "KARANDASH_AI_TRACE"
    private val forced = atomic(false)
    private val truthy = setOf("1", "true", "yes")
    private val unsafeIdChars = Regex("[^a-z0-9_.-]")

    fun enable(on: Boolean = true) {
        forced.value = on
    }

    // read once: the env does not change during a run, and this is checked per AI call
    private val fromEnv: Boolean by lazy { PlatformEnvironment.getEnv(ENV)?.trim()?.lowercase() in truthy }

    val enabled: Boolean
        get() = forced.value || fromEnv

    /**
     * [body] under [header], folded into a collapsed GitLab CI log section when running in GitLab CI
     * (`GITLAB_CI=true`), plain text elsewhere.
     */
    fun section(
        name: String,
        header: String,
        body: String,
        env: (String) -> String? = PlatformEnvironment::getEnv,
    ): String {
        if (env("GITLAB_CI") != "true") return "$header\n$body"
        val id = name.lowercase().replace(unsafeIdChars, "_")
        val now = Clock.System.now().epochSeconds
        return "$ESC[0Ksection_start:$now:$id[collapsed=true]\r$ESC[0K$header\n$body\n$ESC[0Ksection_end:$now:$id\r$ESC[0K"
    }

    private const val ESC = "\u001B"
}
