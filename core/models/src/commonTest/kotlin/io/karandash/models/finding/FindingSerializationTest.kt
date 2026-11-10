package io.karandash.models.finding

import io.karandash.models.support.KarandashJson
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FindingSerializationTest {
    @Test
    fun `new fields have correct defaults`() {
        val finding = Finding(
            severity = Severity.ERROR,
            message = "test",
            rule = "test-rule",
        )
        assertNull(finding.fingerprint)
        assertEquals(Finding.DEFAULT_PRIORITY, finding.priority)
        assertNull(finding.confidence)
        assertEquals(emptyList(), finding.relatedFindings)
        assertNull(finding.category)
        assertNull(finding.errorCode)
    }

    @Test
    fun `round-trip with all new fields populated`() {
        val finding = Finding(
            severity = Severity.WARNING,
            message = "Title too short",
            rule = "title-format",
            fingerprint = "abc123def456",
            priority = 10,
            confidence = 0.95f,
            relatedFindings = listOf("fp1", "fp2"),
            category = FindingCategory.CONVENTION,
            errorCode = "TF-001",
        )
        val json = KarandashJson.Default.encodeToString(finding)
        val decoded = KarandashJson.Default.decodeFromString<Finding>(json)
        assertEquals(finding, decoded)
    }

    @Test
    fun `deserialization without new fields uses defaults`() {
        val json = """
            {
                "severity": "ERROR",
                "message": "test",
                "rule": "test-rule"
            }
        """.trimIndent()
        val finding = KarandashJson.Default.decodeFromString<Finding>(json)
        assertNull(finding.fingerprint)
        assertEquals(Finding.DEFAULT_PRIORITY, finding.priority)
        assertNull(finding.confidence)
        assertEquals(emptyList(), finding.relatedFindings)
        assertNull(finding.category)
        assertNull(finding.errorCode)
    }

    @Test
    fun `confidence boundary values serialize correctly`() {
        val zero = Finding(
            severity = Severity.INFO,
            message = "test",
            rule = "r",
            confidence = 0.0f,
        )
        val one = zero.copy(confidence = 1.0f)
        val zeroDecoded = KarandashJson.Default.decodeFromString<Finding>(
            KarandashJson.Default.encodeToString(zero),
        )
        val oneDecoded = KarandashJson.Default.decodeFromString<Finding>(
            KarandashJson.Default.encodeToString(one),
        )
        assertEquals(0.0f, zeroDecoded.confidence)
        assertEquals(1.0f, oneDecoded.confidence)
    }
}
