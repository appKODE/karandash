package io.karandash.cli.pipeline

import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Severity parsing and ordering that the auto-approve threshold relies on (handler logic: [AutoApproveHandlerTest]). */
class SeverityParsingTest {
    @Test
    fun `severity fromString parses info`() {
        assertEquals(Severity.INFO, Severity.fromString("info"))
    }

    @Test
    fun `severity fromString parses warning`() {
        assertEquals(Severity.WARNING, Severity.fromString("warning"))
    }

    @Test
    fun `severity fromString parses error`() {
        assertEquals(Severity.ERROR, Severity.fromString("error"))
    }

    @Test
    fun `severity fromString returns default for unknown`() {
        assertEquals(Severity.INFO, Severity.fromString("unknown"))
    }

    @Test
    fun `severity ordinals order correctly`() {
        // ERROR(0) < WARNING(1) < INFO(2) - lower ordinal = more severe
        assertTrue(Severity.ERROR.ordinal < Severity.WARNING.ordinal)
        assertTrue(Severity.WARNING.ordinal < Severity.INFO.ordinal)
    }
}
