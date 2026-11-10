package io.karandash.api

import io.karandash.api.context.EnrichmentContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnrichmentContextTest {
    @Test
    fun `EMPTY has no data`() {
        val ctx = EnrichmentContext.EMPTY
        assertTrue(ctx.availableIds.isEmpty())
        assertFalse(ctx.has("anything"))
        assertNull(ctx.get("anything"))
    }

    @Test
    fun `get returns element for existing key`() {
        val element = JsonPrimitive("hello")
        val ctx = EnrichmentContext(mapOf("jira:ticket" to element))

        assertEquals(element, ctx.get("jira:ticket"))
        assertTrue(ctx.has("jira:ticket"))
    }

    @Test
    fun `get returns null for missing key`() {
        val ctx = EnrichmentContext(mapOf("jira:ticket" to JsonPrimitive("hello")))

        assertNull(ctx.get("github:status"))
        assertFalse(ctx.has("github:status"))
    }

    @Test
    fun `availableIds returns all keys`() {
        val ctx = EnrichmentContext(
            mapOf(
                "jira:ticket" to JsonPrimitive("a"),
                "jira:sprint" to JsonPrimitive("b"),
                "github:status" to JsonPrimitive("c"),
            ),
        )

        assertEquals(setOf("jira:ticket", "jira:sprint", "github:status"), ctx.availableIds)
    }

    @Serializable
    data class TicketInfo(
        val key: String,
        val summary: String
    )

    @Test
    fun `getTyped deserializes correctly`() {
        val element = buildJsonObject {
            put("key", "PROJ-123")
            put("summary", "Fix the bug")
        }
        val ctx = EnrichmentContext(mapOf("jira:ticket" to element))

        val ticket = ctx.getTyped<TicketInfo>("jira:ticket")
        assertEquals(TicketInfo("PROJ-123", "Fix the bug"), ticket)
    }

    @Test
    fun `getTyped returns null for missing key`() {
        val ctx = EnrichmentContext.EMPTY
        assertNull(ctx.getTyped<TicketInfo>("jira:ticket"))
    }

    @Test
    fun `getTyped returns null for deserialization failure`() {
        val ctx = EnrichmentContext(mapOf("jira:ticket" to JsonPrimitive("not-an-object")))
        assertNull(ctx.getTyped<TicketInfo>("jira:ticket"))
    }

    @Test
    fun `filter keeps only matching prefixes`() {
        val ctx = EnrichmentContext(
            mapOf(
                "jira:ticket" to JsonPrimitive("a"),
                "jira:sprint" to JsonPrimitive("b"),
                "github:status" to JsonPrimitive("c"),
                "slack:channel" to JsonPrimitive("d"),
            ),
        )

        val filtered = ctx.filter(listOf("jira"))
        assertEquals(setOf("jira:ticket", "jira:sprint"), filtered.availableIds)
        assertTrue(filtered.has("jira:ticket"))
        assertFalse(filtered.has("github:status"))
    }

    @Test
    fun `filter with multiple prefixes`() {
        val ctx = EnrichmentContext(
            mapOf(
                "jira:ticket" to JsonPrimitive("a"),
                "github:status" to JsonPrimitive("b"),
                "slack:channel" to JsonPrimitive("c"),
            ),
        )

        val filtered = ctx.filter(listOf("jira", "slack"))
        assertEquals(setOf("jira:ticket", "slack:channel"), filtered.availableIds)
    }

    @Test
    fun `filter with no matches returns empty`() {
        val ctx = EnrichmentContext(
            mapOf("jira:ticket" to JsonPrimitive("a")),
        )

        val filtered = ctx.filter(listOf("github"))
        assertTrue(filtered.availableIds.isEmpty())
    }

    // --- missingIds tests ---

    @Test
    fun `EMPTY has no missing IDs`() {
        assertFalse(EnrichmentContext.EMPTY.isMissing("anything"))
        assertTrue(EnrichmentContext.EMPTY.missingIds.isEmpty())
    }

    @Test
    fun `isMissing returns true for tracked failures`() {
        val ctx = EnrichmentContext.withMissing(
            data = mapOf("jira:ticket" to JsonPrimitive("a")),
            missingIds = setOf("github:status"),
        )

        assertTrue(ctx.isMissing("github:status"))
        assertFalse(ctx.isMissing("jira:ticket"))
        assertFalse(ctx.isMissing("slack:channel"))
    }

    @Test
    fun `withMissing factory creates context with both data and missingIds`() {
        val ctx = EnrichmentContext.withMissing(
            data = mapOf("a:data" to JsonPrimitive("val")),
            missingIds = setOf("b:data", "c:data"),
        )

        assertTrue(ctx.has("a:data"))
        assertFalse(ctx.has("b:data"))
        assertTrue(ctx.isMissing("b:data"))
        assertTrue(ctx.isMissing("c:data"))
        assertEquals(setOf("b:data", "c:data"), ctx.missingIds)
    }

    @Test
    fun `filter preserves matching missingIds`() {
        val ctx = EnrichmentContext.withMissing(
            data = mapOf(
                "jira:ticket" to JsonPrimitive("a"),
                "github:status" to JsonPrimitive("b"),
            ),
            missingIds = setOf("jira:sprint", "github:checks", "slack:channel"),
        )

        val filtered = ctx.filter(listOf("jira"))

        assertEquals(setOf("jira:ticket"), filtered.availableIds)
        assertTrue(filtered.isMissing("jira:sprint"))
        assertFalse(filtered.isMissing("github:checks"))
        assertFalse(filtered.isMissing("slack:channel"))
    }

    @Test
    fun `filter with empty missingIds preserves empty set`() {
        val ctx = EnrichmentContext(
            data = mapOf("jira:ticket" to JsonPrimitive("a")),
        )

        val filtered = ctx.filter(listOf("jira"))
        assertTrue(filtered.missingIds.isEmpty())
    }

    // --- filterIds tests ---

    @Test
    fun `filterIds keeps only exact ID matches`() {
        val ctx = EnrichmentContext(
            mapOf(
                "jira:ticket" to JsonPrimitive("a"),
                "jira:sprint" to JsonPrimitive("b"),
                "github:status" to JsonPrimitive("c"),
            ),
        )

        val filtered = ctx.filterIds(setOf("jira:ticket", "github:status"))
        assertEquals(setOf("jira:ticket", "github:status"), filtered.availableIds)
        assertFalse(filtered.has("jira:sprint"))
    }

    @Test
    fun `filterIds does not prefix-match`() {
        val ctx = EnrichmentContext(
            mapOf(
                "jira:ticket" to JsonPrimitive("a"),
                "jira:ticket-keys" to JsonPrimitive("b"),
            ),
        )

        val filtered = ctx.filterIds(setOf("jira:ticket"))
        assertEquals(setOf("jira:ticket"), filtered.availableIds)
    }

    @Test
    fun `filterIds with no matches returns empty`() {
        val ctx = EnrichmentContext(mapOf("jira:ticket" to JsonPrimitive("a")))

        val filtered = ctx.filterIds(setOf("github:status"))
        assertTrue(filtered.availableIds.isEmpty())
    }

    @Test
    fun `filterIds preserves matching missingIds`() {
        val ctx = EnrichmentContext.withMissing(
            data = mapOf("jira:ticket" to JsonPrimitive("a")),
            missingIds = setOf("jira:sprint", "github:status"),
        )

        val filtered = ctx.filterIds(setOf("jira:ticket", "jira:sprint"))
        assertTrue(filtered.isMissing("jira:sprint"))
        assertFalse(filtered.isMissing("github:status"))
    }
}
