package io.karandash.utils.finding

import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FindingFactoryTest {
    @Test
    fun `error creates ERROR severity finding`() {
        val finding = FindingFactory.error("test-rule", "Test error message")

        assertEquals(Severity.ERROR, finding.severity)
        assertEquals("test-rule", finding.rule)
        assertEquals("Test error message", finding.message)
        assertNull(finding.location)
        assertNull(finding.explanation)
    }

    @Test
    fun `warn creates WARNING severity finding`() {
        val finding = FindingFactory.warn("test-rule", "Test warning message")

        assertEquals(Severity.WARNING, finding.severity)
        assertEquals("test-rule", finding.rule)
        assertEquals("Test warning message", finding.message)
        assertNull(finding.location)
        assertNull(finding.explanation)
    }

    @Test
    fun `info creates INFO severity finding`() {
        val finding = FindingFactory.info("test-rule", "Test info message")

        assertEquals(Severity.INFO, finding.severity)
        assertEquals("test-rule", finding.rule)
        assertEquals("Test info message", finding.message)
        assertNull(finding.location)
        assertNull(finding.explanation)
    }

    @Test
    fun `error with file creates location with file only`() {
        val finding = FindingFactory.error("test-rule", "Test message", file = "path/to/file.kt")

        val loc = finding.location as? Location.FileRef
        assertNotNull(loc)
        assertEquals("path/to/file.kt", loc.path)
        assertNull(loc.line)
    }

    @Test
    fun `error with line only creates no location`() {
        val finding = FindingFactory.error("test-rule", "Test message", line = 42)

        // Without a file path, FileRef cannot be constructed; location is null
        assertNull(finding.location)
    }

    @Test
    fun `error with file and line creates complete location`() {
        val finding = FindingFactory.error("test-rule", "Test message", file = "path/to/file.kt", line = 42)

        val loc = finding.location as? Location.FileRef
        assertNotNull(loc)
        assertEquals("path/to/file.kt", loc.path)
        assertEquals(42, loc.line)
    }

    @Test
    fun `error with explanation includes explanation`() {
        val finding = FindingFactory.error(
            "test-rule",
            "Test message",
            explanation = "This is why it failed"
        )

        assertEquals("This is why it failed", finding.explanation)
    }

    @Test
    fun `error with suggestion includes suggestion`() {
        val finding = FindingFactory.error(
            "test-rule",
            "Test message",
            suggestion = "Try this instead"
        )

        assertEquals("Try this instead", finding.suggestion)
    }

    @Test
    fun `error with both explanation and suggestion includes both`() {
        val finding = FindingFactory.error(
            "test-rule",
            "Test message",
            explanation = "This is why",
            suggestion = "Try this"
        )

        assertEquals("This is why", finding.explanation)
        assertEquals("Try this", finding.suggestion)
    }

    @Test
    fun `warn with suggestion includes suggestion`() {
        val finding = FindingFactory.warn(
            "test-rule",
            "Test warning",
            suggestion = "Fix suggestion"
        )

        assertEquals("Fix suggestion", finding.suggestion)
    }

    @Test
    fun `info with suggestion includes suggestion`() {
        val finding = FindingFactory.info(
            "test-rule",
            "Test info",
            suggestion = "Info suggestion"
        )

        assertEquals("Info suggestion", finding.suggestion)
    }

    @Test
    fun `default suggestion is null`() {
        val finding = FindingFactory.error("test-rule", "Test message")
        assertNull(finding.suggestion)
    }

    @Test
    fun `warn with all parameters creates complete finding`() {
        val finding = FindingFactory.warn(
            rule = "test-rule",
            message = "Test warning",
            file = "src/Main.kt",
            line = 10,
            explanation = "Detailed explanation"
        )

        assertEquals(Severity.WARNING, finding.severity)
        assertEquals("test-rule", finding.rule)
        assertEquals("Test warning", finding.message)
        val loc = finding.location as? Location.FileRef
        assertNotNull(loc)
        assertEquals("src/Main.kt", loc.path)
        assertEquals(10, loc.line)
        assertEquals("Detailed explanation", finding.explanation)
    }

    @Test
    fun `info with all parameters creates complete finding`() {
        val finding = FindingFactory.info(
            rule = "test-rule",
            message = "Test info",
            file = "src/Main.kt",
            line = 20,
            explanation = "Additional context"
        )

        assertEquals(Severity.INFO, finding.severity)
        assertEquals("test-rule", finding.rule)
        assertEquals("Test info", finding.message)
        val loc = finding.location as? Location.FileRef
        assertNotNull(loc)
        assertEquals("src/Main.kt", loc.path)
        assertEquals(20, loc.line)
        assertEquals("Additional context", finding.explanation)
    }
}
