package io.karandash.enrichment.localgit

import io.karandash.api.platform.ChargeResult
import io.karandash.api.platform.SubprocessBudget
import io.karandash.platform.GitRunner
import io.karandash.platform.ProcessTimeouts
import io.karandash.sdk.Logger
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

/**
 * Thin wrapper around [GitRunner.git] for the git invocations
 * produced by [LocalGitEnrichmentProvider] and related analyzers.
 *
 * Adds the per-run [SubprocessBudget] on top of [GitRunner] (timeout, unavailable
 * git, non-zero exit code) so the analyzer code can stay linear and focused on parsing.
 *
 * The helper does NOT sanitize arguments - callers are expected to pass
 * argv lists that they have already validated via `GitArgSanitizer` if any
 * element is user-derived.
 */
internal object GitCliHelper {
    /** Default wall-clock timeout per git invocation (30 seconds). */
    const val DEFAULT_TIMEOUT_SECONDS: Int = ProcessTimeouts.DEFAULT_SECONDS

    /**
     * Execute `git <args>` and return the captured output. Returns null when
     * git is missing, the exit code is non-zero, or the subprocess times out.
     * Rethrows [CancellationException] so coroutine cancellation still works.
     *
     * When [budget] is non-null, the helper short-circuits to null if the
     * per-run budget is already exhausted, and otherwise charges the elapsed
     * wall-clock time after the subprocess returns. Budget rejections are
     * logged once per reason at WARN level via [logger] when provided.
     */
    suspend fun run(
        workingDir: String?,
        args: List<String>,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
        budget: SubprocessBudget? = null,
        logger: Logger? = null,
    ): String? {
        val command = listOf("git") + args
        if (budget != null && (budget.remainingCalls <= 0 || budget.remainingWallMillis <= 0)) {
            logger?.warn(
                "git helper: subprocess budget exhausted before running " +
                    "${command.firstOrNull()} ${args.firstOrNull() ?: ""} — skipping"
            )
            // A skipped call is a rejection too, so callers see `hasRejected` and report partial data.
            budget.charge(command, durationMs = 1)
            return null
        }
        val start = TimeSource.Monotonic.markNow()
        // A subprocess failure is expected (git missing, not a repository) and surfaces as null.
        val output: String? = GitRunner.Default.git(args, workingDir, timeoutSeconds)
        if (budget != null) {
            val elapsedMs = start.elapsedNow().inWholeMilliseconds
            chargeAndLog(budget, command, elapsedMs, logger)
        }
        return output
    }

    /** Charges [elapsedMs] to [budget] and warns once per exhausted limit; internal so tests pick the duration. */
    internal fun chargeAndLog(
        budget: SubprocessBudget,
        command: List<String>,
        elapsedMs: Long,
        logger: Logger?,
    ) {
        when (val charge = budget.charge(command, elapsedMs)) {
            is ChargeResult.Accepted -> Unit

            is ChargeResult.RejectedCount -> if (charge.firstWarning) {
                logger?.warn(
                    "git helper: subprocess call budget exhausted " +
                        "(${charge.used}/${charge.limit} after '${charge.head}')"
                )
            }

            is ChargeResult.RejectedTime -> if (charge.firstWarning) {
                logger?.warn(
                    "git helper: subprocess wall-time budget exhausted " +
                        "(${charge.usedMillis}/${charge.limitMillis}ms after '${charge.head}')"
                )
            }
        }
    }
}
