package io.karandash.plugins.ai.core.config

import io.karandash.api.config.PreserveKeys
import io.karandash.duration.KarandashDuration
import io.karandash.helpers.RegexHelper
import io.karandash.models.MergeRequest
import io.karandash.models.ReviewState
import io.karandash.plugins.ai.core.pipeline.CorrelationCondition
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Grace period windows: temporary rule relaxation with automatic expiry.
 *
 * Grace periods allow on-call engineers or release coordinators to bypass specific rules
 * for a bounded window (e.g., hotfix during an incident). Each grace period activates
 * based on a simple condition evaluated against the current MR.
 */
object GracePeriodEvaluator {
    /**
     * Returns the first active grace period whose condition matches the given MR, or null.
     *
     * @param mr The merge request to evaluate conditions against.
     * @param gracePeriods Configured grace period windows.
     * @param warn Receives the message for a condition that does not parse; that grace period does not apply.
     * @return The first matching [GracePeriodConfig], or null when none apply.
     */
    fun findActiveGracePeriod(
        mr: MergeRequest,
        gracePeriods: List<GracePeriodConfig>,
        warn: (String) -> Unit = {},
    ): GracePeriodConfig? = gracePeriods.firstOrNull { gp -> evaluateCondition(mr, gp, warn) }

    /**
     * whether [grace] may apply to the MR [mrKey] at [now], given its past uses in [state].
     * The first use by an MR starts its window, which ends after [GracePeriodConfig.maxDuration]; a new MR is
     * refused once [GracePeriodConfig.maxUsesPerDay] MRs have used the grace period on the same UTC day.
     * Either limit unset (null) is not enforced.
     */
    fun admit(
        grace: GracePeriodConfig,
        mrKey: String,
        now: Instant,
        state: GraceUsageState,
    ): GraceAdmission {
        // limit: uses older than USAGE_RETENTION (or maxDuration, when longer) are dropped, so the store stays small
        val maxDuration = grace.maxDuration?.takeIf { it != KarandashDuration.NEVER }?.toKotlinDuration()
        val retention = maxOf(USAGE_RETENTION, maxDuration ?: USAGE_RETENTION)
        val uses = state.uses[grace.name]
            .orEmpty()
            .mapNotNull { use -> parseInstant(use.startedAt)?.takeIf { now - it < retention }?.let { use to it } }
        val kept = uses.map { it.first }
        val own = uses.firstOrNull { it.first.mrKey == mrKey }
        if (own != null) {
            val endsAt = maxDuration?.let { own.second + it }
            return GraceAdmission(state.with(grace.name, kept), endsAt?.takeIf { now > it }?.let { "expired at $it" })
        }
        val today = utcDay(now)
        val usedToday = uses.count { utcDay(it.second) == today }
        if (grace.maxUsesPerDay != null && usedToday >= grace.maxUsesPerDay) {
            return GraceAdmission(state.with(grace.name, kept), "used by $usedToday MRs today, maxUsesPerDay ${grace.maxUsesPerDay}")
        }
        return GraceAdmission(state.with(grace.name, kept + GraceUse(mrKey, now.toString())))
    }

    private fun GraceUsageState.with(
        name: String,
        kept: List<GraceUse>,
    ): GraceUsageState = copy(uses = uses + (name to kept))

    private fun utcDay(instant: Instant): Long = instant.epochSeconds.floorDiv(1.days.inWholeSeconds)

    private val USAGE_RETENTION = 30.days

    private const val GRACE_START = "grace-period-start"
    private const val GRACE_END = "grace-period-end"

    /**
     * `ai.gracePeriod`: the grace window opened by the last `@karandash grace-period-start` tag not followed by
     * `grace-period-end`, or null when none is open. [GraceWindow.blockedReason] is set when the window is open
     * but not in force: too few approvals, or expired. The window starts at the approval that met
     * [GracePeriodWindowConfig.approvalRequired]; without a timestamp for it (or with `approvalRequired: 0`)
     * the window has no known start and does not expire.
     */
    fun openWindow(
        mr: MergeRequest,
        config: GracePeriodWindowConfig,
        now: Instant,
    ): GraceWindow? {
        if (!config.enabled) return null
        val tags = InlineMrOverrideParser.parseTags(mr.description)
        val start = tags.indexOfLast { it.tag == GRACE_START }
        if (start < 0 || tags.drop(start + 1).any { it.tag == GRACE_END }) return null
        val approvals = mr.approvals.filter { it.state == ReviewState.APPROVED }
        val reason = tags[start].reason
        if (approvals.size < config.approvalRequired) {
            return GraceWindow(reason, approvals.size, blockedReason = "${approvals.size}/${config.approvalRequired} approvals")
        }
        val startedAt = approvals
            .takeIf { config.approvalRequired > 0 }
            ?.mapNotNull { parseInstant(it.approvedAt) }
            ?.sorted()
            ?.getOrNull(config.approvalRequired - 1)
        val expiresAt = startedAt?.plus(config.durationMinutes.minutes)
        val blocked = expiresAt?.takeIf { now > it }?.let { "expired at $it" }
        return GraceWindow(reason, approvals.size, expiresAt?.toString(), blocked)
    }

    private fun parseInstant(value: String?): Instant? =
        try {
            value?.let(Instant::parse)
        } catch (e: IllegalArgumentException) {
            null // an unparseable timestamp counts as missing: the window then has no known start
        }

    /**
     * The three short forms (either quote style, spaces allowed) keep their meaning; anything else goes to the
     * shared conditions engine (`has_label`, `is_draft`, `branch *= "hotfix-*"`, `&&`, ...).
     */
    private fun evaluateCondition(
        mr: MergeRequest,
        grace: GracePeriodConfig,
        warn: (String) -> Unit,
    ): Boolean {
        val condition = grace.condition.trim()
        if (condition == "draft") return mr.draft
        val short = SHORT_CALL.matchEntire(condition)
        if (short != null) {
            val (call, _, argument) = short.destructured
            return if (call == "labels.contains") {
                argument in mr.labels
            } else {
                mr.sourceBranch.contains(argument) || RegexHelper.compileOrNull(argument)?.containsMatchIn(mr.sourceBranch) == true
            }
        }
        return try {
            CorrelationCondition.evaluator(mr).evaluate(condition)
        } catch (e: IllegalArgumentException) {
            warn("Grace period '${grace.name}': condition '$condition' does not parse (${e.message}); it does not apply")
            false
        } catch (e: IllegalStateException) {
            warn("Grace period '${grace.name}': condition '$condition' does not parse (${e.message}); it does not apply")
            false
        }
    }

    private val SHORT_CALL = Regex("""^(labels\.contains|branch\.matches)\(\s*(['"])(.*)\2\s*\)$""")
}

/** Configuration for a single grace period window. */
@Serializable
data class GracePeriodConfig(
    /** Human-readable name for this grace period (e.g., "incident-hotfix"). */
    val name: String,
    /**
     * Activation condition expression:
     * - `labels.contains('<label>')` - true when the MR carries the given label (single or double quotes)
     * - `branch.matches('<pattern>')` - true when source branch contains or matches the pattern
     * - `draft` - true when the MR is in draft state
     * - any other `when` expression of the shared conditions engine (`has_label(x) && !is_draft`); one that does
     *   not parse is reported as a warning and never activates the grace period
     */
    val condition: String,
    /** Maximum duration of the grace window, from the MR's first use of it (needs AI state). Null: no limit. */
    val maxDuration: KarandashDuration? = null,
    /** Maximum number of MRs that may start this grace period per UTC day (needs AI state). Null: no limit. */
    val maxUsesPerDay: Int? = null,
    /** When true, grace period activations are recorded in the audit log. */
    val audit: Boolean = true,
    /**
     * Rule-level overrides applied while the grace period is active.
     * Maps rule ID to a new severity or `"skip"` to bypass the rule entirely.
     */
    @PreserveKeys val overrideRules: Map<String, String> = emptyMap(),
)

/** the MRs that used each grace period (by [GracePeriodConfig.name]), kept across runs. */
@Serializable
data class GraceUsageState(
    val uses: Map<String, List<GraceUse>> = emptyMap(),
)

/** One MR's use of a grace period; [startedAt] is the ISO-8601 instant of its first use. */
@Serializable
data class GraceUse(
    val mrKey: String,
    val startedAt: String,
)

/** [GracePeriodEvaluator.admit]: the usage to save, and why the grace period is not applied (null when it is). */
@Serializable
data class GraceAdmission(
    val state: GraceUsageState,
    val blockedReason: String? = null,
)

/** `ai.gracePeriod`: an approval-gated, time-limited window opened by an MR description tag. */
@Serializable
data class GracePeriodWindowConfig(
    val enabled: Boolean = true,
    val durationMinutes: Int = DEFAULT_GRACE_MINUTES,
    /** Approvals needed before the window takes effect. */
    val approvalRequired: Int = 0,
    /** Log the active window at warn level. */
    val auditLog: Boolean = true,
)

private const val DEFAULT_GRACE_MINUTES = 60

/** An open grace window; in force when [blockedReason] is null. [expiresAt] is ISO-8601, null when unknown. */
@Serializable
data class GraceWindow(
    val reason: String,
    val approvals: Int,
    val expiresAt: String? = null,
    val blockedReason: String? = null,
)
