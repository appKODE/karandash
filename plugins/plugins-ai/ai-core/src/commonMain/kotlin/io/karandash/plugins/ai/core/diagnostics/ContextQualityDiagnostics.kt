package io.karandash.plugins.ai.core.diagnostics

import io.karandash.api.platform.ChargeResult
import io.karandash.logger.Logger

/**
 * Single entry-point for every observability event emitted by the AI context
 * quality pipeline. Gives operators a stable, consistent telemetry surface:
 * subprocess lifecycle, provider skips, block inclusion decisions, finding
 * drops, convention detections, truncation decisions, and budget exhaustion.
 *
 * All events use structured logging via [Logger.withContext] so a downstream
 * parser can group by `source=context-quality`. Level choices:
 * - `debug`: per-event traces that are noisy (every subprocess, every block).
 * - `info`: one-shot decisions that change pipeline behaviour (provider skip,
 *           bridge fallback, convention detection).
 * - `warn`: anomalies that might hide a bug (dropped finding, budget underflow).
 */
class ContextQualityDiagnostics(
    private val logger: Logger,
) {
    private val scopedLogger: Logger = logger.withContext("source", SOURCE_TAG)

    /** Called immediately before a subprocess is spawned. */
    fun subprocessStart(
        source: String,
        command: List<String>,
        workingDir: String?
    ) {
        val head = command.firstOrNull() ?: ""
        val argCount = (command.size - 1).coerceAtLeast(0)
        scopedLogger
            .withContext(KEY_EVENT, "subprocess-start")
            .withContext(KEY_PROVIDER, source)
            .withContext("command", head)
            .withContext("argCount", argCount.toString())
            .withContext("workingDir", workingDir ?: "")
            .debug("subprocess-start")
    }

    /** Called once a subprocess has completed (success or failure). */
    fun subprocessEnd(
        source: String,
        command: List<String>,
        durationMs: Long,
        exitCode: Int,
    ) {
        val head = command.firstOrNull() ?: ""
        scopedLogger
            .withContext(KEY_EVENT, "subprocess-end")
            .withContext(KEY_PROVIDER, source)
            .withContext("command", head)
            .withContext("durationMs", durationMs.toString())
            .withContext("exitCode", exitCode.toString())
            .debug("subprocess-end")
    }

    /** Reason a context provider decided to produce no output. */
    fun providerSkipped(
        providerId: String,
        reason: SkipReason
    ) {
        scopedLogger
            .withContext(KEY_EVENT, "provider-skipped")
            .withContext(KEY_PROVIDER, providerId)
            .withContext(KEY_REASON, reason.token)
            .info("provider-skipped: $providerId ($reason)")
    }

    /** Context block was included in the final prompt. */
    fun blockIncluded(
        providerId: String,
        heading: String,
        priority: Int,
        chars: Int,
        remainingBudget: Int,
    ) {
        scopedLogger
            .withContext(KEY_EVENT, "block-included")
            .withContext(KEY_PROVIDER, providerId)
            .withContext("heading", heading)
            .withContext("priority", priority.toString())
            .withContext("chars", chars.toString())
            .withContext("remainingBudget", remainingBudget.toString())
            .debug("block-included: $heading")
    }

    /** Context block was not included (budget, empty, or disabled). */
    fun blockSkipped(
        providerId: String,
        heading: String,
        chars: Int,
        remainingBudget: Int,
        reason: BlockSkipReason,
    ) {
        scopedLogger
            .withContext(KEY_EVENT, "block-skipped")
            .withContext(KEY_PROVIDER, providerId)
            .withContext("heading", heading)
            .withContext("chars", chars.toString())
            .withContext("remainingBudget", remainingBudget.toString())
            .withContext(KEY_REASON, reason.token)
            .info("block-skipped: $heading ($reason)")
    }

    /** AI-produced finding dropped by FindingLocationValidator. */
    fun findingDropped(
        ruleId: String,
        path: String?,
        line: Int?,
        reason: FindingDropReason
    ) {
        scopedLogger
            .withContext(KEY_EVENT, "finding-dropped")
            .withContext("rule", ruleId)
            .withContext("path", path ?: "")
            .withContext("line", line?.toString() ?: "")
            .withContext(KEY_REASON, reason.token)
            .warn("finding-dropped: rule=$ruleId path=${path ?: "<mr>"} line=${line ?: "<mr>"} ($reason)")
    }

    /** Project convention file detected and extracted. */
    fun conventionDetected(
        path: String,
        type: String,
        sizeBytes: Int
    ) {
        scopedLogger
            .withContext(KEY_EVENT, "convention-detected")
            .withContext("path", path)
            .withContext("type", type)
            .withContext("sizeBytes", sizeBytes.toString())
            .info("convention-detected: $type at $path ($sizeBytes bytes)")
    }

    /** Per-file truncation decision - kept/dropped hunk count for traceability. */
    fun truncationDecision(
        path: String,
        strategy: String,
        fileBudget: Int,
        keptHunks: Int,
        droppedHunks: Int,
        charsPerToken: Int = DEFAULT_CHARS_PER_TOKEN,
    ) {
        val budgetTokens = fileBudget / charsPerToken.coerceAtLeast(1)
        scopedLogger
            .withContext(KEY_EVENT, "truncation-decision")
            .withContext("path", path)
            .withContext("strategy", strategy)
            .withContext("fileBudget", fileBudget.toString())
            .withContext("fileBudgetTokens", budgetTokens.toString())
            .withContext("keptHunks", keptHunks.toString())
            .withContext("droppedHunks", droppedHunks.toString())
            .debug("truncation-decision: $path $strategy budget=~$budgetTokens tokens kept=$keptHunks dropped=$droppedHunks")
    }

    /** Emitted once when a proportional allocation can't give every file the floor. */
    fun budgetUnderflow(
        totalBudget: Int,
        fileCount: Int,
        minFloor: Int
    ) {
        scopedLogger
            .withContext(KEY_EVENT, "budget-underflow")
            .withContext("totalBudget", totalBudget.toString())
            .withContext("fileCount", fileCount.toString())
            .withContext("minFloor", minFloor.toString())
            .warn(
                "budget-underflow: totalBudget=$totalBudget fileCount=$fileCount minFloor=$minFloor " +
                    "— some files will receive less than the normal floor",
            )
    }

    /** A platform-level fallback endpoint has been engaged. */
    fun bridgeFallback(
        endpoint: String,
        reason: String
    ) {
        scopedLogger
            .withContext(KEY_EVENT, "bridge-fallback")
            .withContext("endpoint", endpoint)
            .withContext(KEY_REASON, reason)
            .info("bridge-fallback: $endpoint ($reason)")
    }

    /**
     * Emit a warning for a subprocess budget rejection. Prefers a once-per-reason
     * message guided by [ChargeResult.RejectedCount.firstWarning] /
     * [ChargeResult.RejectedTime.firstWarning].
     */
    fun budgetRejected(
        providerId: String,
        result: ChargeResult
    ) {
        when (result) {
            is ChargeResult.Accepted -> return

            is ChargeResult.RejectedCount -> if (result.firstWarning) {
                scopedLogger
                    .withContext(KEY_EVENT, "subprocess-budget-rejected")
                    .withContext(KEY_PROVIDER, providerId)
                    .withContext(KEY_REASON, "call-count")
                    .withContext("used", result.used.toString())
                    .withContext("limit", result.limit.toString())
                    .warn("subprocess-budget-rejected: provider=$providerId reason=call-count used=${result.used} limit=${result.limit}")
            }

            is ChargeResult.RejectedTime -> if (result.firstWarning) {
                scopedLogger
                    .withContext(KEY_EVENT, "subprocess-budget-rejected")
                    .withContext(KEY_PROVIDER, providerId)
                    .withContext(KEY_REASON, "wall-millis")
                    .withContext("usedMillis", result.usedMillis.toString())
                    .withContext("limitMillis", result.limitMillis.toString())
                    .warn(
                        "subprocess-budget-rejected: provider=$providerId reason=wall-millis " +
                            "usedMillis=${result.usedMillis} limitMillis=${result.limitMillis}",
                    )
            }
        }
    }

    companion object {
        const val SOURCE_TAG: String = "context-quality"
        private const val KEY_EVENT: String = "event"
        private const val KEY_PROVIDER: String = "provider"
        private const val KEY_REASON: String = "reason"
        private const val DEFAULT_CHARS_PER_TOKEN: Int = 4
    }
}

/** Why a provider produced no output. */
enum class SkipReason(
    val token: String
) {
    NO_GIT("no-git"),
    NOT_REPO("not-repo"),
    SHALLOW_CLONE("shallow-clone"),
    NO_WORKING_DIR("no-working-dir"),
    DISABLED("disabled"),
    BUDGET_EXHAUSTED("budget-exhausted"),
    EMPTY_INPUT("empty-input"),
    FAILURE("failure"),
}

/** Why a context block was not included. */
enum class BlockSkipReason(
    val token: String
) {
    OVER_BUDGET("over-budget"),
    EMPTY("empty"),
    DISABLED("disabled"),
}

/** Reason a finding was dropped by the location validator or the actionability filter. */
enum class FindingDropReason(
    val token: String
) {
    PATH_UNKNOWN("path-unknown"),
    NOT_CHANGED("not-changed"),
    OUT_OF_RANGE("out-of-range"),
    HEDGED("hedged"),
}
