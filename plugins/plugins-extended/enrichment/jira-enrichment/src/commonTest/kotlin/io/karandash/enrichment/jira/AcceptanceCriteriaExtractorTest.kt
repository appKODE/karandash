package io.karandash.enrichment.jira

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AcceptanceCriteriaExtractorTest {
    // ── ADF builder helpers ───────────────────────────────────────────────────

    private fun adfDoc(vararg blocks: kotlinx.serialization.json.JsonObject) =
        buildJsonObject {
            put("type", "doc")
            put("content", buildJsonArray { blocks.forEach { add(it) } })
        }

    private fun adfHeading(
        level: Int,
        text: String
    ) = buildJsonObject {
        put("type", "heading")
        put("attrs", buildJsonObject { put("level", level) })
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                )
            }
        )
    }

    private fun adfBulletList(vararg items: String) =
        buildJsonObject {
            put("type", "bulletList")
            put(
                "content",
                buildJsonArray {
                    items.forEach { item ->
                        add(
                            buildJsonObject {
                                put("type", "listItem")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "paragraph")
                                                put(
                                                    "content",
                                                    buildJsonArray {
                                                        add(
                                                            buildJsonObject {
                                                                put("type", "text")
                                                                put("text", item)
                                                            }
                                                        )
                                                    }
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }

    private fun adfParagraph(text: String) =
        buildJsonObject {
            put("type", "paragraph")
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        }
                    )
                }
            )
        }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `returns empty list when description is null and no custom field`() {
        val result = AcceptanceCriteriaExtractor.extract(descriptionAdf = null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extracts from custom field ADF`() {
        val customField = adfDoc(adfBulletList("User can log in", "Session persists across pages"))
        val result = AcceptanceCriteriaExtractor.extract(
            descriptionAdf = null,
            customFieldAdf = customField,
        )
        assertEquals(2, result.size)
        assertTrue(result.contains("User can log in"))
        assertTrue(result.contains("Session persists across pages"))
    }

    @Test
    fun `extracts from heading-based description`() {
        val description = adfDoc(
            adfParagraph("Background context here."),
            adfHeading(2, "Acceptance Criteria"),
            adfBulletList("Given user is authenticated", "When visiting /home then dashboard appears"),
        )
        val result = AcceptanceCriteriaExtractor.extract(descriptionAdf = description)
        assertEquals(2, result.size)
        assertTrue(result.contains("Given user is authenticated"))
        assertTrue(result.contains("When visiting /home then dashboard appears"))
    }

    @Test
    fun `heading extraction stops at next heading`() {
        val description = adfDoc(
            adfHeading(2, "Acceptance Criteria"),
            adfBulletList("AC item one"),
            adfHeading(2, "Implementation Notes"),
            adfBulletList("Not an AC item"),
        )
        val result = AcceptanceCriteriaExtractor.extract(descriptionAdf = description)
        assertEquals(1, result.size)
        assertEquals("AC item one", result[0])
    }

    @Test
    fun `merges custom field and heading without duplicates`() {
        val customField = adfDoc(adfBulletList("AC one", "AC two"))
        val description = adfDoc(
            adfHeading(2, "AC"),
            adfBulletList("AC two", "AC three"),
        )
        val result = AcceptanceCriteriaExtractor.extract(
            descriptionAdf = description,
            customFieldAdf = customField,
        )
        assertEquals(3, result.size, "Expected 3 unique items but got: $result")
        assertTrue(result.contains("AC one"))
        assertTrue(result.contains("AC two"))
        assertTrue(result.contains("AC three"))
    }

    @Test
    fun `heading matching is case insensitive`() {
        val description = adfDoc(
            adfHeading(3, "acceptance criteria"),
            adfBulletList("Lower case heading works"),
        )
        val result = AcceptanceCriteriaExtractor.extract(descriptionAdf = description)
        assertEquals(1, result.size)
        assertEquals("Lower case heading works", result[0])
    }

    @Test
    fun `returns empty when no AC section found`() {
        val description = adfDoc(
            adfParagraph("Just background info."),
            adfHeading(2, "Implementation Details"),
            adfBulletList("Not acceptance criteria"),
        )
        val result = AcceptanceCriteriaExtractor.extract(descriptionAdf = description)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `normalises bullet prefixes`() {
        val description = adfDoc(
            adfHeading(2, "Acceptance Criteria"),
            adfBulletList("  item with leading spaces  "),
        )
        val result = AcceptanceCriteriaExtractor.extract(descriptionAdf = description)
        assertEquals(1, result.size)
        assertEquals("item with leading spaces", result[0])
    }
}
