package io.karandash.config

import io.karandash.duration.KarandashDuration
import kotlinx.serialization.Serializable

/**
 * Unified timeout configuration for all pipeline stages.
 *
 * Provides a single config block for all timeout-related settings,
 * replacing scattered raw `Long` millis and mixed duration types.
 *
 * ```yaml
 * timeouts:
 *   http: 45s
 *   enrichment: 15s
 *   plugin-shutdown: 10s
 *   cache-ttl: 60s
 *   rule-execution: 120s
 *   scripts: 90s
 *   dangerfile: 5m
 * ```
 */
@Serializable
data class TimeoutConfig(
    /** HTTP request, connect, and socket timeout for provider API calls (an explicit `ai.timeout` wins for AI calls). */
    val http: KarandashDuration = DEFAULT_HTTP,
    /** Maximum time for a single enrichment provider call. */
    val enrichment: KarandashDuration = DEFAULT_ENRICHMENT,
    /** Maximum time to wait for a single module's shutdown call when the CLI exits. */
    val pluginShutdown: KarandashDuration = DEFAULT_PLUGIN_SHUTDOWN,
    /**
     * TTL for the provider response cache when `cache.responses.ttl` is unset (that key wins).
     * `KarandashLimits.DEFAULT_CACHE_TTL_SECONDS` (30m) is unrelated and unused here.
     */
    val cacheTtl: KarandashDuration = DEFAULT_CACHE_TTL,
    /**
     * Maximum time for a single rule's validate call; unset = no per-rule timeout. A timed-out rule
     * contributes no findings plus one engine warning. AI rules get the larger of this and `ai.executionTimeout`.
     */
    val ruleExecution: KarandashDuration? = null,
    /**
     * Cumulative wall-clock cap for all `scripts:` in one run; unset = the sum of the per-script timeouts
     * (never trims). Once spent, remaining scripts are skipped with a warning naming each.
     */
    val scripts: KarandashDuration? = null,
    /** Wall-clock cap for the Dangerfile subprocess (`node`/`kotlinc`); a Dangerfile still running is killed. */
    val dangerfile: KarandashDuration = DEFAULT_DANGERFILE,
    /**
     * Global watchdog timeout for the entire CLI execution, measured from process start. 0 = no timeout.
     * `--global-timeout` wins; the config value re-arms the watchdog once the config is loaded.
     */
    val global: KarandashDuration = DEFAULT_GLOBAL,
) {
    companion object {
        val DEFAULT_HTTP: KarandashDuration = KarandashDuration.ofSeconds(KarandashLimits.DEFAULT_HTTP_TIMEOUT_SECONDS)
        val DEFAULT_ENRICHMENT: KarandashDuration = KarandashDuration.ofSeconds(10)
        val DEFAULT_PLUGIN_SHUTDOWN: KarandashDuration = KarandashDuration.ofSeconds(10)
        val DEFAULT_CACHE_TTL: KarandashDuration = KarandashDuration.ofMinutes(1)

        /** Suggested `ruleExecution` value; not applied by default (an unset `ruleExecution` means no timeout). */
        val DEFAULT_RULE_EXECUTION: KarandashDuration = KarandashDuration.ofSeconds(60)
        val DEFAULT_GLOBAL: KarandashDuration = KarandashDuration.ofMinutes(10)
        val DEFAULT_DANGERFILE: KarandashDuration = KarandashDuration.ofMinutes(5)
    }
}
