package io.karandash.utils.finding

import io.karandash.models.finding.Finding
import io.karandash.models.finding.FindingCategory
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FindingBuilderTest {
    @Test
    fun `FindingFactory builds through the finding DSL`() {
        val fix = listOf(FixSuggestion.AddLabel("bug"))
        val viaFactory = FindingFactory.warn("r", "m", "a.kt", 3, "why", "do", mapOf("k" to "v"), fix)
        val viaDsl = finding("r", Severity.WARNING, "m") {
            file = "a.kt"
            line = 3
            explanation = "why"
            suggestion = "do"
            metadata = mapOf("k" to "v")
            fixSuggestions = fix
        }

        assertEquals(viaDsl, viaFactory)
        assertEquals(Location.FileRef("a.kt", 3), viaFactory.location)
        assertNull(FindingFactory.error("r", "m").location)
    }

    @Test
    fun `builder with new fields creates finding correctly`() {
        val result = finding("test-rule", Severity.ERROR, "Test message") {
            fingerprint = "fp123"
            priority = 10
            confidence = 0.9f
            relatedFindings = listOf("fp456")
            category = FindingCategory.SECURITY
            errorCode = "SEC-001"
        }
        assertEquals("fp123", result.fingerprint)
        assertEquals(10, result.priority)
        assertEquals(0.9f, result.confidence)
        assertEquals(listOf("fp456"), result.relatedFindings)
        assertEquals(FindingCategory.SECURITY, result.category)
        assertEquals("SEC-001", result.errorCode)
    }

    @Test
    fun `builder defaults match Finding defaults`() {
        val result = finding("test-rule", Severity.INFO, "msg")
        assertNull(result.fingerprint)
        assertEquals(Finding.DEFAULT_PRIORITY, result.priority)
        assertNull(result.confidence)
        assertEquals(emptyList(), result.relatedFindings)
        assertNull(result.category)
        assertNull(result.errorCode)
    }
}
