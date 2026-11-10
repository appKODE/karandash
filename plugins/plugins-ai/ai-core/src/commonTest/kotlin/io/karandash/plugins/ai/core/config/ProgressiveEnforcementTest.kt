package io.karandash.plugins.ai.core.config

import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressiveEnforcementTest {
    @Test
    fun `empty schedule returns ENFORCE`() {
        assertEquals(EnforcementMode.ENFORCE, ProgressiveEnforcement.resolveMode(emptyList(), "2024-06-01"))
    }

    @Test
    fun `active WARN phase is resolved`() {
        val schedule = listOf(
            EnforcementPhase(from = "2024-01-01", until = "2024-12-31", mode = EnforcementMode.WARN),
        )
        assertEquals(EnforcementMode.WARN, ProgressiveEnforcement.resolveMode(schedule, "2024-06-01"))
    }

    @Test
    fun `active WARN phase downgrades ERROR to WARNING`() {
        assertEquals(Severity.WARNING, ProgressiveEnforcement.adjustSeverity(Severity.ERROR, EnforcementMode.WARN))
    }

    @Test
    fun `active WARN phase leaves WARNING unchanged`() {
        assertEquals(Severity.WARNING, ProgressiveEnforcement.adjustSeverity(Severity.WARNING, EnforcementMode.WARN))
    }

    @Test
    fun `active WARN phase leaves INFO unchanged`() {
        assertEquals(Severity.INFO, ProgressiveEnforcement.adjustSeverity(Severity.INFO, EnforcementMode.WARN))
    }

    @Test
    fun `PARTIAL mode blocks severity in blockSeverities`() {
        assertEquals(
            Severity.ERROR,
            ProgressiveEnforcement.adjustSeverity(Severity.ERROR, EnforcementMode.PARTIAL, setOf(Severity.ERROR)),
        )
    }

    @Test
    fun `PARTIAL mode downgrades ERROR not in blockSeverities`() {
        assertEquals(
            Severity.WARNING,
            ProgressiveEnforcement.adjustSeverity(Severity.ERROR, EnforcementMode.PARTIAL, emptySet()),
        )
    }

    @Test
    fun `PARTIAL mode leaves WARNING unchanged when not in blockSeverities`() {
        assertEquals(
            Severity.WARNING,
            ProgressiveEnforcement.adjustSeverity(Severity.WARNING, EnforcementMode.PARTIAL, setOf(Severity.ERROR)),
        )
    }

    @Test
    fun `ENFORCE mode passes ERROR through unchanged`() {
        assertEquals(Severity.ERROR, ProgressiveEnforcement.adjustSeverity(Severity.ERROR, EnforcementMode.ENFORCE))
    }

    @Test
    fun `ENFORCE mode passes WARNING through unchanged`() {
        assertEquals(Severity.WARNING, ProgressiveEnforcement.adjustSeverity(Severity.WARNING, EnforcementMode.ENFORCE))
    }

    @Test
    fun `ENFORCE mode passes INFO through unchanged`() {
        assertEquals(Severity.INFO, ProgressiveEnforcement.adjustSeverity(Severity.INFO, EnforcementMode.ENFORCE))
    }

    @Test
    fun `schedule resolves latest matching phase when multiple overlap`() {
        val schedule = listOf(
            EnforcementPhase(from = "2024-01-01", mode = EnforcementMode.WARN),
            EnforcementPhase(from = "2024-04-01", mode = EnforcementMode.PARTIAL),
        )
        assertEquals(EnforcementMode.PARTIAL, ProgressiveEnforcement.resolveMode(schedule, "2024-06-01"))
    }

    @Test
    fun `phase before from date is not yet active`() {
        val schedule = listOf(
            EnforcementPhase(from = "2024-07-01", mode = EnforcementMode.WARN),
        )
        assertEquals(EnforcementMode.ENFORCE, ProgressiveEnforcement.resolveMode(schedule, "2024-06-01"))
    }

    @Test
    fun `expired phase falls back to ENFORCE`() {
        val schedule = listOf(
            EnforcementPhase(from = "2024-01-01", until = "2024-03-31", mode = EnforcementMode.WARN),
        )
        assertEquals(EnforcementMode.ENFORCE, ProgressiveEnforcement.resolveMode(schedule, "2024-06-01"))
    }

    @Test
    fun `phase with null until never expires`() {
        val schedule = listOf(
            EnforcementPhase(from = "2024-01-01", until = null, mode = EnforcementMode.PARTIAL),
        )
        assertEquals(EnforcementMode.PARTIAL, ProgressiveEnforcement.resolveMode(schedule, "2099-01-01"))
    }

    @Test
    fun `from date boundary is inclusive`() {
        val schedule = listOf(
            EnforcementPhase(from = "2024-06-01", mode = EnforcementMode.WARN),
        )
        assertEquals(EnforcementMode.WARN, ProgressiveEnforcement.resolveMode(schedule, "2024-06-01"))
    }

    @Test
    fun `until date boundary is inclusive`() {
        val schedule = listOf(
            EnforcementPhase(from = "2024-01-01", until = "2024-06-01", mode = EnforcementMode.WARN),
        )
        assertEquals(EnforcementMode.WARN, ProgressiveEnforcement.resolveMode(schedule, "2024-06-01"))
    }
}
