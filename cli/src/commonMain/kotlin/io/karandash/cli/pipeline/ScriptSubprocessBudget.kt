package io.karandash.cli.pipeline

import io.karandash.api.platform.SubprocessBudget
import io.karandash.config.Config

/**
 * Per-run subprocess budget for `scripts:`. Unset `timeouts.scripts` never trims a run: unlimited calls and a
 * wall budget equal to the sum of the per-script timeouts (a script that exhausts its own timeout still fits).
 * An explicit `timeouts.scripts` caps the cumulative wall time; later scripts are skipped with a warning naming them.
 */
internal fun scriptSubprocessBudget(config: Config): SubprocessBudget {
    val explicit = config.timeouts?.scripts?.millis
    val wall =
        explicit ?: config.scripts.orEmpty().map { it.timeout.millis }.let { all ->
            if (all.any { it <= 0L }) Long.MAX_VALUE else all.fold(0L) { acc, ms -> acc + ms }.coerceAtLeast(0L)
        }
    return SubprocessBudget(maxCalls = Int.MAX_VALUE, maxWallMillis = wall)
}
