package io.karandash.plugins.ai.core

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.math.ceil
import kotlin.time.Clock

/**
 * Temporal rule composition: escalates finding severity based on per-author repeat violations.
 *
 * Tracks how many times each author has triggered each rule across MRs.
 * First offense = INFO, repeated = WARNING, habitual = ERROR.
 *
 * State is immutable: [recordViolations] replaces it with a `copy()`. Callers load it from
 * [AiStatePersistence.loadTemporalState] and save [currentState] afterwards.
 *
 * @lifecycle per-run -- do not reuse across invocations
 *
 * @param state Current author violation state (loaded from persistence).
 * @param escalationWindow Number of recent MRs to consider for escalation.
 * @param config `ai.temporal`: memory window in days and escalation factor (defaults keep every recorded
 *   violation and the 2 / 5 repeat thresholds).
 * @param nowMillis Clock used for recording and for the memory window.
 */
class TemporalRuleTracker(
    private var state: TemporalState = TemporalState(),
    private val escalationWindow: Int = DEFAULT_WINDOW,
    private val config: TemporalConfig = TemporalConfig(),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val repeatThreshold = scaledThreshold(REPEAT_THRESHOLD)
    private val habitualThreshold = scaledThreshold(HABITUAL_THRESHOLD)

    /** Divides [base] by the escalation factor, so a factor of 2 escalates after half the violations. */
    private fun scaledThreshold(base: Int): Int {
        val factor = config.escalationFactor.takeIf { it > 0.0 } ?: 1.0
        return ceil(base / factor).toInt().coerceAtLeast(1)
    }

    /**
     * Applies temporal escalation to findings based on author history.
     *
     * @param findings Current validation findings.
     * @param authorId The MR author's identifier.
     * @return Findings with potentially escalated severities wrapped in [TemporalResult].
     */
    fun applyEscalation(
        findings: List<Finding>,
        authorId: String,
    ): TemporalResult {
        var escalatedCount = 0

        val escalated = findings.map { finding ->
            val ruleViolations = getViolationCount(authorId, finding.rule)
            val newSeverity = escalateSeverity(finding.severity, ruleViolations)

            if (newSeverity != finding.severity) {
                escalatedCount++
                finding.copy(
                    severity = newSeverity,
                    metadata = finding.metadata +
                        ("temporalEscalation" to "true") +
                        ("priorViolations" to ruleViolations.toString()),
                )
            } else {
                finding
            }
        }

        return TemporalResult(
            findings = escalated,
            escalatedCount = escalatedCount,
        )
    }

    /**
     * Records the current MR's violations for future escalation.
     *
     * Only non-INFO findings are counted as violations.
     *
     * @param authorId The MR author's identifier.
     * @param findings Findings from the current MR validation.
     */
    fun recordViolations(
        authorId: String,
        findings: List<Finding>
    ) {
        val ruleViolations = findings
            .filter { it.severity != Severity.INFO }
            .map { it.rule }
            .toSet()

        if (ruleViolations.isEmpty()) return

        val now = nowMillis()
        val authorState = state.authors[authorId] ?: AuthorViolationState()
        val history = authorState.ruleHistory + ruleViolations.associateWith { rule ->
            ((authorState.ruleHistory[rule] ?: emptyList()) + now).takeLast(escalationWindow)
        }
        state = state.copy(authors = state.authors + (authorId to authorState.copy(ruleHistory = history)))
    }

    /** The state after any [recordViolations] calls, for persistence. */
    fun currentState(): TemporalState = state

    /**
     * Serializes the current state for persistence.
     *
     * @return JSON string representation of [TemporalState].
     */
    fun serializeState(): String = json.encodeToString(TemporalState.serializer(), state)

    private fun getViolationCount(
        authorId: String,
        rule: String
    ): Int {
        val authorState = state.authors[authorId] ?: return 0
        val history = authorState.ruleHistory[rule] ?: return 0
        val memoryDays = config.violationMemory ?: return history.size
        val cutoff = nowMillis() - memoryDays * MILLIS_PER_DAY
        return history.count { it >= cutoff }
    }

    private fun escalateSeverity(
        current: Severity,
        priorViolations: Int
    ): Severity =
        when {
            priorViolations >= habitualThreshold -> {
                Severity.ERROR
            }

            priorViolations >= repeatThreshold -> {
                if (current == Severity.INFO) Severity.WARNING else current
            }

            else -> {
                current
            }
        }

    companion object {
        private const val DEFAULT_WINDOW = 10
        private const val REPEAT_THRESHOLD = 2
        private const val HABITUAL_THRESHOLD = 5
        private const val MILLIS_PER_DAY = 86_400_000L

        private val json = KarandashJson.IgnoreUnknown

        /**
         * Deserializes state from a persisted JSON string.
         *
         * @param jsonStr JSON string previously produced by [TemporalRuleTracker.serializeState].
         * @return Deserialized [TemporalState], or a fresh empty state on parse failure.
         */
        fun deserializeState(jsonStr: String): TemporalState =
            try {
                json.decodeFromString(TemporalState.serializer(), jsonStr)
            } catch (_: SerializationException) {
                TemporalState()
            } catch (_: IllegalArgumentException) {
                TemporalState()
            }
    }
}

/**
 * `ai.temporal`: cross-MR repeat-violation escalation.
 *
 * @param enabled Turns escalation on (as `ai.pipeline.temporalEscalation: true` does).
 * @param trackViolations `false` escalates from the stored history without recording this MR's violations.
 * @param violationMemory Days a recorded violation counts toward escalation; `null` = every recorded one.
 * @param escalationFactor Divides the repeat (2) and habitual (5) thresholds; 2.0 escalates twice as fast.
 */
@Serializable
data class TemporalConfig(
    val enabled: Boolean = true,
    val trackViolations: Boolean = true,
    val violationMemory: Int? = null,
    val escalationFactor: Double = 1.0,
)

/** Per-author violation history across MRs. */
@Serializable
data class TemporalState(
    val authors: Map<String, AuthorViolationState> = emptyMap(),
)

/** Violation history for a single author: maps rule ID to list of epoch-millisecond timestamps. */
@Serializable
data class AuthorViolationState(
    val ruleHistory: Map<String, List<Long>> = emptyMap(),
)

/**
 * Result of applying temporal escalation to a set of findings.
 *
 * @param findings Findings with potentially escalated severities.
 * @param escalatedCount Number of findings whose severity was escalated.
 */
@Serializable
data class TemporalResult(
    val findings: List<Finding>,
    val escalatedCount: Int,
)
