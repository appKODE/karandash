package io.karandash.validators

import io.karandash.patterns.TicketFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossEntityStructuredConfigTest {
    // ------------------------------------------------------------------
    // Test 1: JIRA with projects generates project-scoped pattern
    // ------------------------------------------------------------------

    @Test
    fun `ticket-format jira with single project generates scoped pattern`() {
        val config = CrossEntityConsistencyConfig(
            ticketFormat = TicketFormat.JIRA,
            ticketProjects = listOf("MAPS"),
        )
        val pattern = config.effectiveTaskIdPattern()

        assertEquals("\\bMAPS-\\d+", pattern)
        assertTrue(Regex(pattern).matches("MAPS-123"))
        assertTrue(!Regex(pattern).matches("OTHER-123"))
    }

    // ------------------------------------------------------------------
    // Test 2: GITHUB ticket format generates hash pattern
    // ------------------------------------------------------------------

    @Test
    fun `ticket-format github generates hash-number pattern`() {
        val config = CrossEntityConsistencyConfig(
            ticketFormat = TicketFormat.GITHUB,
        )
        val pattern = config.effectiveTaskIdPattern()

        assertEquals("#\\d+", pattern)
        assertTrue(Regex(pattern).matches("#42"))
        assertTrue(!Regex(pattern).matches("MAPS-42"))
    }

    // ------------------------------------------------------------------
    // Test 3: explicit taskIdPattern overrides structured fields
    // ------------------------------------------------------------------

    @Test
    fun `explicit taskIdPattern overrides ticketFormat and ticketProjects`() {
        val config = CrossEntityConsistencyConfig(
            taskIdPattern = "PROJ-\\d+",
            ticketFormat = TicketFormat.JIRA,
            ticketProjects = listOf("MAPS"),
        )
        val pattern = config.effectiveTaskIdPattern()

        assertEquals("PROJ-\\d+", pattern, "Explicit taskIdPattern must take precedence")
    }

    // ------------------------------------------------------------------
    // Test 4: default (all null) uses generic Jira-like pattern
    // ------------------------------------------------------------------

    @Test
    fun `default config with all null uses generic uppercase-key pattern`() {
        val config = CrossEntityConsistencyConfig()
        val pattern = config.effectiveTaskIdPattern()

        assertEquals("[A-Z]+-\\d+", pattern)
        assertTrue(Regex(pattern).containsMatchIn("MAPS-123"))
        assertTrue(Regex(pattern).containsMatchIn("ANYTHING-999"))
    }

    // ------------------------------------------------------------------
    // Test 5: multiple projects generate alternation pattern
    // ------------------------------------------------------------------

    @Test
    fun `multiple projects generate alternation in pattern`() {
        val config = CrossEntityConsistencyConfig(
            ticketFormat = TicketFormat.JIRA,
            ticketProjects = listOf("MAPS", "ALPHA"),
        )
        val pattern = config.effectiveTaskIdPattern()

        assertEquals("\\b(?:MAPS|ALPHA)-\\d+", pattern)
        assertTrue(Regex(pattern).containsMatchIn("MAPS-10"))
        assertTrue(Regex(pattern).containsMatchIn("ALPHA-10"))
    }

    // ------------------------------------------------------------------
    // Additional: LINEAR format with project generates scoped pattern
    // ------------------------------------------------------------------

    @Test
    fun `ticket-format linear with project generates scoped pattern`() {
        val config = CrossEntityConsistencyConfig(
            ticketFormat = TicketFormat.LINEAR,
            ticketProjects = listOf("ENG"),
        )
        val pattern = config.effectiveTaskIdPattern()

        assertEquals("\\bENG-\\d+", pattern)
        assertTrue(Regex(pattern).matches("ENG-42"))
    }

    // ------------------------------------------------------------------
    // Additional: SHORTCUT format generates sc-\\d+ pattern
    // ------------------------------------------------------------------

    @Test
    fun `ticket-format shortcut generates sc-number pattern`() {
        val config = CrossEntityConsistencyConfig(
            ticketFormat = TicketFormat.SHORTCUT,
        )
        val pattern = config.effectiveTaskIdPattern()

        assertEquals("sc-\\d+", pattern)
        assertTrue(Regex(pattern).matches("sc-1234"))
        assertTrue(!Regex(pattern).matches("MAPS-1234"))
    }

    // ------------------------------------------------------------------
    // Additional: CUSTOM format falls through to default pattern
    // ------------------------------------------------------------------

    @Test
    fun `ticket-format custom without explicit taskIdPattern falls back to default`() {
        val config = CrossEntityConsistencyConfig(
            ticketFormat = TicketFormat.CUSTOM,
        )
        val pattern = config.effectiveTaskIdPattern()

        assertEquals("[A-Z]+-\\d+", pattern, "CUSTOM format with no taskIdPattern should use default")
    }

    // ------------------------------------------------------------------
    // Additional: JIRA without projects uses generic uppercase-key fallback
    // ------------------------------------------------------------------

    @Test
    fun `ticket-format jira without projects uses generic key fallback`() {
        val config = CrossEntityConsistencyConfig(
            ticketFormat = TicketFormat.JIRA,
        )
        val pattern = config.effectiveTaskIdPattern()

        assertEquals("\\b[A-Z]+-\\d+", pattern)
        assertTrue(Regex(pattern).containsMatchIn("ANYTHING-1"))
    }
}
