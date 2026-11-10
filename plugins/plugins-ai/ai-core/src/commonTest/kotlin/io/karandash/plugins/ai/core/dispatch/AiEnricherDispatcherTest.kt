package io.karandash.plugins.ai.core.dispatch

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.BlockBudget
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiEnricherDispatcherTest {
    @Test
    fun emptyEnricherListProducesEmptyDispatcher() {
        val dispatcher = AiEnricherDispatcher(emptyList())
        assertNull(dispatcher.enricherFor("jira:tickets"))
        assertTrue(dispatcher.handledIds.isEmpty())
    }

    @Test
    fun singleEnricherRoutesByExactId() {
        val enricher = stubEnricher("ticket-enricher", listOf("jira:tickets", "github:issues"), priority = 100)
        val dispatcher = AiEnricherDispatcher(listOf(enricher))
        assertEquals(enricher, dispatcher.enricherFor("jira:tickets"))
        assertEquals(enricher, dispatcher.enricherFor("github:issues"))
        assertNull(dispatcher.enricherFor("jira:unknown"))
    }

    @Test
    fun multipleEnrichersRoutedToCorrectOne() {
        val ticketEnricher = stubEnricher("ticket", listOf("jira:tickets"), priority = 100)
        val ciEnricher = stubEnricher("ci", listOf("ci:failed-jobs", "ci:coverage-gaps"), priority = 200)
        val dispatcher = AiEnricherDispatcher(listOf(ticketEnricher, ciEnricher))
        assertEquals(ticketEnricher, dispatcher.enricherFor("jira:tickets"))
        assertEquals(ciEnricher, dispatcher.enricherFor("ci:failed-jobs"))
        assertEquals(ciEnricher, dispatcher.enricherFor("ci:coverage-gaps"))
    }

    @Test
    fun clashResolvesLowerPriorityNumberWins() {
        val highPriority = stubEnricher("high", listOf("jira:tickets"), priority = 50)
        val lowPriority = stubEnricher("low", listOf("jira:tickets"), priority = 300)
        val dispatcher = AiEnricherDispatcher(listOf(highPriority, lowPriority))
        assertEquals(highPriority, dispatcher.enricherFor("jira:tickets"))
    }

    @Test
    fun clashCallbackInvokedForConflictingId() {
        val first = stubEnricher("first", listOf("jira:tickets"), priority = 100)
        val second = stubEnricher("second", listOf("jira:tickets"), priority = 200)
        val clashes = mutableListOf<Triple<String, String, String>>()
        AiEnricherDispatcher(listOf(first, second)) { id, winner, loser ->
            clashes.add(Triple(id, winner.id, loser.id))
        }
        assertEquals(1, clashes.size)
        assertEquals("jira:tickets", clashes[0].first)
        assertEquals("first", clashes[0].second)
        assertEquals("second", clashes[0].third)
    }

    @Test
    fun handledIdsContainsAllRegisteredIds() {
        val e1 = stubEnricher("e1", listOf("jira:tickets", "jira:acceptance-criteria"), priority = 100)
        val e2 = stubEnricher("e2", listOf("confluence:pages"), priority = 200)
        val dispatcher = AiEnricherDispatcher(listOf(e1, e2))
        assertEquals(setOf("jira:tickets", "jira:acceptance-criteria", "confluence:pages"), dispatcher.handledIds)
    }

    @Test
    fun enricherWithNoHandledIdsIsIgnoredInIndex() {
        val fsEnricher = stubEnricher("fs-enricher", emptyList(), priority = 100)
        val dispatcher = AiEnricherDispatcher(listOf(fsEnricher))
        assertTrue(dispatcher.handledIds.isEmpty())
    }

    @Test
    fun unknownIdReturnsNull() {
        val enricher = stubEnricher("e", listOf("jira:tickets"), priority = 100)
        val dispatcher = AiEnricherDispatcher(listOf(enricher))
        assertNull(dispatcher.enricherFor("snyk:vulnerabilities"))
    }

    @Test
    fun manyEnrichersWithManyIdsMappedCorrectly() {
        val enrichers = (1..10).map { i ->
            stubEnricher("e$i", listOf("id:${i}a", "id:${i}b"), priority = i * 10)
        }
        val dispatcher = AiEnricherDispatcher(enrichers)
        for (i in 1..10) {
            assertNotNull(dispatcher.enricherFor("id:${i}a"))
            assertNotNull(dispatcher.enricherFor("id:${i}b"))
        }
        assertEquals(20, dispatcher.handledIds.size)
    }

    private fun stubEnricher(
        enricherId: String,
        ids: List<String>,
        priority: Int,
    ): AiContextEnricherModule =
        object : AiContextEnricherModule {
            override val id: String = enricherId
            override val name: String = enricherId
            override val version: String = "1.0.0"
            override val enricherPriority: Int = priority

            override fun handledEnrichmentIds(): List<String> = ids

            override suspend fun enrich(
                mr: MergeRequest,
                context: AiContextEnricherContext
            ): Map<String, String> = emptyMap()

            override suspend fun provideBlock(
                mr: MergeRequest,
                changes: List<FileChange>,
                config: JsonElement,
                budget: BlockBudget
            ): AiContextBlock? = null

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun shutdown() {}
        }
}
