package io.karandash.plugins.ai.core.config

import io.karandash.models.finding.Severity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Progressive enforcement: time-based rollout of rule strictness.
 *
 * Enables gradual adoption: warnings-only → block critical only → full enforcement.
 * Each phase has a date range and enforcement mode.
 */
object ProgressiveEnforcement {
    /**
     * Determines the current enforcement mode based on the schedule and current date.
     *
     * Selects the latest phase whose [EnforcementPhase.from] date is on or before
     * [currentDateIso], provided it has not yet expired (i.e. [EnforcementPhase.until]
     * is null or on/after [currentDateIso]). ISO date strings sort lexicographically,
     * so string comparison is correct for YYYY-MM-DD format.
     *
     * @param schedule List of enforcement phases ordered by start date.
     * @param currentDateIso Current date in ISO format (YYYY-MM-DD).
     * @return The active enforcement mode, or [EnforcementMode.ENFORCE] if no phase matches.
     */
    fun resolveMode(
        schedule: List<EnforcementPhase>,
        currentDateIso: String
    ): EnforcementMode = resolvePhase(schedule, currentDateIso)?.mode ?: EnforcementMode.ENFORCE

    /** The phase [resolveMode] takes its mode from, or null when none is active on [currentDateIso]. */
    fun resolvePhase(
        schedule: List<EnforcementPhase>,
        currentDateIso: String,
    ): EnforcementPhase? =
        schedule
            .filter { it.from <= currentDateIso }
            .maxByOrNull { it.from }
            ?.takeIf { it.until == null || it.until >= currentDateIso }

    /**
     * the severities a phase's `blockSeverities` names (case-insensitive; unknown names dropped), or
     * `ERROR` when it names none, as before the key was read.
     */
    fun blockSeverities(phase: EnforcementPhase): Set<Severity> =
        phase.blockSeverities
            .mapNotNull { name -> Severity.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            .toSet()
            .ifEmpty { setOf(Severity.ERROR) }

    /**
     * Adjusts finding severity based on enforcement mode.
     *
     * - [EnforcementMode.WARN]: All ERROR findings downgraded to WARNING (never block).
     * - [EnforcementMode.PARTIAL]: Only severities in [blockSeverities] are kept as-is;
     *   ERROR findings not in [blockSeverities] are downgraded to WARNING.
     * - [EnforcementMode.ENFORCE]: Severity is returned unchanged.
     *
     * @param severity The original finding severity.
     * @param mode The active enforcement mode.
     * @param blockSeverities Severities that remain blocking under PARTIAL mode.
     * @return The effective severity after applying the enforcement mode.
     */
    fun adjustSeverity(
        severity: Severity,
        mode: EnforcementMode,
        blockSeverities: Set<Severity> = setOf(Severity.ERROR),
    ): Severity =
        when (mode) {
            EnforcementMode.WARN -> if (severity == Severity.ERROR) Severity.WARNING else severity

            EnforcementMode.PARTIAL -> if (severity in blockSeverities) {
                severity
            } else {
                if (severity == Severity.ERROR) Severity.WARNING else severity
            }

            EnforcementMode.ENFORCE -> severity
        }
}

/** A single phase in a progressive enforcement schedule. */
@Serializable
data class EnforcementPhase(
    /** ISO date (YYYY-MM-DD) when this phase starts. */
    val from: String,
    /** ISO date (YYYY-MM-DD) when this phase ends, inclusive. Null means no expiry. */
    val until: String? = null,
    /** Enforcement mode active during this phase. */
    val mode: EnforcementMode = EnforcementMode.ENFORCE,
    /** Severity names that remain blocking under [EnforcementMode.PARTIAL]. */
    val blockSeverities: Set<String> = emptySet(),
)

/** Enforcement strictness level for a progressive rollout phase. */
@Serializable
enum class EnforcementMode {
    /** All findings reported as warnings; nothing blocks the pipeline. */
    @SerialName("warn")
    WARN,

    /** Only specified severities block; others are downgraded to warnings. */
    @SerialName("partial")
    PARTIAL,

    /** Full strictness; all findings reported at their original severity. */
    @SerialName("enforce")
    ENFORCE,
}
