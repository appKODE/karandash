package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import kotlin.time.Instant

/**
 * Configuration and time-based predicates for condition expressions.
 *
 * Includes bare predicates for config state checks, function predicates
 * for profile/module queries, and comparison predicates for MR age metrics.
 */
object ConfigPredicates {
    /**
     * Registers all config and time predicates into [registry].
     */
    fun registerAll(registry: PredicateRegistry<ConditionContext>) {
        registry.register(IsDryRunPredicate)
        registry.register(HasProfilePredicate)
        registry.register(ProfileIsPredicate)
        registry.register(ModuleConfiguredPredicate)
        registry.register(MrAgeHoursPredicate)
        registry.register(MrAgeDaysPredicate)
        registry.register(LastCommitAgeHoursPredicate)
    }
}

// ── Bare predicates ─────────────────────────────────────────────────────

private object IsDryRunPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "is_dry_run"
    override val arity: Int = 1
    override val isBarePredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean = context.dryRun
}

private object HasProfilePredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "has_profile"
    override val arity: Int = 1
    override val isBarePredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean = context.profileId != null
}

// ── Function predicates ─────────────────────────────────────────────────

private object ProfileIsPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "profile_is"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val id = args.getOrNull(0) ?: return false
        return context.profileId == id
    }
}

private object ModuleConfiguredPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "module_configured"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val id = args.getOrNull(0) ?: return false
        return id in context.configuredModuleIds
    }
}

// ── Time-based comparison predicates ────────────────────────────────────

private object MrAgeHoursPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "mr_age_hours"
    override val arity: Int = 2
    override val isComparisonPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val op = compOpOrNull(args.getOrNull(0)) ?: return false
        val value = args.getOrNull(1)?.toIntOrNull() ?: return false
        val ageHours = mrAgeHours(context)
        return ExpressionEvaluator.compareInt(ageHours, op, value)
    }
}

private object MrAgeDaysPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "mr_age_days"
    override val arity: Int = 2
    override val isComparisonPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val op = compOpOrNull(args.getOrNull(0)) ?: return false
        val value = args.getOrNull(1)?.toIntOrNull() ?: return false
        val ageDays = mrAgeHours(context) / HOURS_PER_DAY
        return ExpressionEvaluator.compareInt(ageDays, op, value)
    }
}

private object LastCommitAgeHoursPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "last_commit_age_hours"
    override val arity: Int = 2
    override val isComparisonPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val op = compOpOrNull(args.getOrNull(0)) ?: return false
        val value = args.getOrNull(1)?.toIntOrNull() ?: return false
        if (context.nowEpochSeconds <= 0L) return false
        val latestCommitEpoch = context.mr.commits
            .mapNotNull { parseIso8601ToEpochSeconds(it.timestamp) }
            .maxOrNull() ?: return false
        val ageHours = ((context.nowEpochSeconds - latestCommitEpoch) / SECONDS_PER_HOUR).toInt()
        return ExpressionEvaluator.compareInt(ageHours, op, value)
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────

private const val HOURS_PER_DAY = 24
private const val SECONDS_PER_HOUR = 3600L

/**
 * Computes MR age in hours using the earliest commit timestamp as a proxy
 * for MR creation time (MergeRequest has no createdAt field).
 */
private fun mrAgeHours(context: ConditionContext): Int {
    if (context.nowEpochSeconds <= 0L) return 0
    val earliestCommitEpoch = context.mr.commits
        .mapNotNull { parseIso8601ToEpochSeconds(it.timestamp) }
        .minOrNull() ?: return 0
    return ((context.nowEpochSeconds - earliestCommitEpoch) / SECONDS_PER_HOUR).toInt()
}

/**
 * Parses an ISO 8601 timestamp (`2024-03-15T10:30:00Z`, `2024-03-15T10:30:00+02:00`) to epoch seconds.
 * A timestamp without a zone is read as UTC. Returns `null` for unparseable timestamps.
 */
private fun parseIso8601ToEpochSeconds(timestamp: String): Long? {
    val normalized = timestamp.trim()
    return parseInstantOrNull(normalized) ?: parseInstantOrNull("${normalized}Z")
}

private fun parseInstantOrNull(text: String): Long? =
    try {
        Instant.parse(text).epochSeconds
    } catch (_: IllegalArgumentException) {
        null
    }
