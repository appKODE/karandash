package io.karandash.config

import io.karandash.duration.KarandashDuration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuration for runtime diagnostics (memory/CPU monitoring).
 *
 * ```yaml
 * diagnostics:
 *   output: diagnostics/diagnostic.json
 *   interval: 500ms
 *   flush-interval: 5s
 * ```
 *
 * When [output] is set (via config or `--diagnostic` CLI flag), the pipeline
 * collects periodic resource snapshots and writes them to the specified file.
 * Periodic flushing keeps data in the file even when the pipeline
 * is killed by a timeout or CI job cancellation.
 */
@Serializable
data class DiagnosticConfig(
    /** Output file path for the diagnostic JSON report. Null disables diagnostics. */
    val output: String? = null,
    /** Sampling interval for resource snapshots. */
    val interval: KarandashDuration = DEFAULT_INTERVAL,
    /** How often to flush collected data to the output file. */
    @SerialName("flushInterval")
    val flushInterval: KarandashDuration = DEFAULT_FLUSH_INTERVAL,
) {
    companion object {
        val DEFAULT_INTERVAL: KarandashDuration = KarandashDuration.ofMillis(500)
        val DEFAULT_FLUSH_INTERVAL: KarandashDuration = KarandashDuration.ofSeconds(5)
    }
}
