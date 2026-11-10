package io.karandash.models.finding

import io.karandash.models.support.KarandashJson
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

class FindingCategoryTest {
    @Test
    fun `serialization round-trip preserves all categories`() {
        for (category in FindingCategory.entries) {
            val json = KarandashJson.Default.encodeToString(category)
            val decoded = KarandashJson.Default.decodeFromString<FindingCategory>(json)
            assertEquals(category, decoded)
        }
    }

    @Test
    fun `serialization produces expected JSON strings`() {
        assertEquals("\"STYLE\"", KarandashJson.Default.encodeToString(FindingCategory.STYLE))
        assertEquals("\"SECURITY\"", KarandashJson.Default.encodeToString(FindingCategory.SECURITY))
        assertEquals("\"CI_CD\"", KarandashJson.Default.encodeToString(FindingCategory.CI_CD))
    }

    @Test
    fun `entries contains all expected categories`() {
        val names = FindingCategory.entries.map { it.name }.toSet()
        val expected = setOf(
            "STYLE",
            "CONVENTION",
            "SECURITY",
            "PERFORMANCE",
            "CORRECTNESS",
            "DOCUMENTATION",
            "DEPENDENCY",
            "CI_CD",
            "WORKFLOW",
        )
        assertEquals(expected, names)
    }
}
