package io.karandash.api.platform

import kotlinx.atomicfu.atomic
import kotlinx.serialization.Serializable

/**
 * Per-run budget tracker for external subprocess invocations (mainly git CLI calls
 * from context providers). Prevents runaway process spawning on large MRs.
 *
 * Two limits are enforced independently:
 * - [maxCalls]: maximum number of [charge] calls that can be accepted.
 * - [maxWallMillis]: cumulative wall-clock ms across all accepted calls.
 *
 * Exhaustion is not an error - [charge] returns [ChargeResult.Rejected]. Callers
 * should log and fall back to a skip path. A single [ChargeResult.Rejected] log
 * per exhaustion reason is emitted at WARN level.
 *
 * Concurrency-safe via [kotlinx.atomicfu] atomic counters. Safe to share across
 * parallel providers dispatched from a single validation run.
 *
 * @lifecycle per-run - construct a new instance for each CLI invocation. Mixing
 *     across runs causes budget to leak between unrelated validations.
 */
public class SubprocessBudget(
    public val maxCalls: Int = DEFAULT_MAX_CALLS,
    public val maxWallMillis: Long = DEFAULT_MAX_WALL_MILLIS,
) {
    private val callsUsed = atomic(0)
    private val wallUsedMillis = atomic(0L)
    private val warnedCallExhaustion = atomic(false)
    private val warnedWallExhaustion = atomic(false)

    /** Calls counted against [maxCalls] (including rejected attempts). */
    public val callsAttempted: Int get() = callsUsed.value

    /** True once any [charge] was rejected (call count or wall time exceeded). */
    public val hasRejected: Boolean get() = warnedCallExhaustion.value || warnedWallExhaustion.value

    /** Remaining call budget, never negative. */
    public val remainingCalls: Int get() = (maxCalls - callsUsed.value).coerceAtLeast(0)

    /** Wall-clock ms already charged. */
    public val wallMillisUsed: Long get() = wallUsedMillis.value

    /** Remaining wall-clock ms budget, never negative. */
    public val remainingWallMillis: Long get() = (maxWallMillis - wallUsedMillis.value).coerceAtLeast(0L)

    /**
     * Charge the budget for a completed subprocess invocation. Always advances the
     * counters; callers inspect the returned [ChargeResult] to decide whether to
     * short-circuit further work.
     *
     * @param command Command vector (only the first element is retained for logs).
     * @param durationMs Wall-clock duration of the charged invocation.
     * @return [ChargeResult.Accepted] when budget remains; [ChargeResult.RejectedCount]
     *     when the call count has been exceeded; [ChargeResult.RejectedTime] when the
     *     wall budget has been exceeded. Emits at most one warning flag per reason.
     */
    public fun charge(
        command: List<String>,
        durationMs: Long
    ): ChargeResult {
        val nextCalls = callsUsed.incrementAndGet()
        val nextWallMillis = wallUsedMillis.addAndGet(durationMs.coerceAtLeast(0L))

        if (nextCalls > maxCalls) {
            val firstWarn = warnedCallExhaustion.compareAndSet(expect = false, update = true)
            return ChargeResult.RejectedCount(
                head = command.firstOrNull() ?: "",
                used = nextCalls,
                limit = maxCalls,
                firstWarning = firstWarn,
            )
        }

        if (nextWallMillis > maxWallMillis) {
            val firstWarn = warnedWallExhaustion.compareAndSet(expect = false, update = true)
            return ChargeResult.RejectedTime(
                head = command.firstOrNull() ?: "",
                usedMillis = nextWallMillis,
                limitMillis = maxWallMillis,
                firstWarning = firstWarn,
            )
        }

        return ChargeResult.Accepted(
            head = command.firstOrNull() ?: "",
            durationMs = durationMs,
            remainingCalls = remainingCalls,
            remainingWallMillis = remainingWallMillis,
        )
    }

    public companion object {
        /** Default maximum subprocess invocations per run. */
        public const val DEFAULT_MAX_CALLS: Int = 30

        /** Default cumulative wall-clock ms per run (30 seconds). */
        public const val DEFAULT_MAX_WALL_MILLIS: Long = 30_000L

        /**
         * Sensible per-run budget for AI context enrichment pipelines.
         * Allows 30 subprocess calls with a 30-second cumulative wall-clock limit.
         *
         * Returns a fresh instance on every call - required because [SubprocessBudget]
         * holds mutable atomicfu counters that must not be shared across CLI invocations.
         */
        public fun default(): SubprocessBudget =
            SubprocessBudget(
                maxCalls = DEFAULT_MAX_CALLS,
                maxWallMillis = DEFAULT_MAX_WALL_MILLIS,
            )

        /**
         * Default budget used by [io.karandash.api.PluginContext] when the host pipeline has not provided a per-run
         * instance. Never rejects; per-run pipelines override with a real [SubprocessBudget].
         */
        public val UNLIMITED: SubprocessBudget = SubprocessBudget(
            maxCalls = Int.MAX_VALUE,
            maxWallMillis = Long.MAX_VALUE,
        )
    }
}

/** Outcome of [SubprocessBudget.charge]. */
public sealed class ChargeResult {
    /** Budget consumed successfully. */
    @Serializable
    public data class Accepted(
        val head: String,
        val durationMs: Long,
        val remainingCalls: Int,
        val remainingWallMillis: Long,
    ) : ChargeResult()

    /** Call-count budget exhausted. `firstWarning=true` only on the first rejection per reason. */
    @Serializable
    public data class RejectedCount(
        val head: String,
        val used: Int,
        val limit: Int,
        val firstWarning: Boolean,
    ) : ChargeResult()

    /** Wall-clock budget exhausted. `firstWarning=true` only on the first rejection per reason. */
    @Serializable
    public data class RejectedTime(
        val head: String,
        val usedMillis: Long,
        val limitMillis: Long,
        val firstWarning: Boolean,
    ) : ChargeResult()
}
