package io.karandash.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnrichmentGlobMatcherTest {
    private val ids = setOf("jira:tickets", "jira:ticket-keys", "gitlab:mr", "ci:failed-jobs")

    @Test
    fun `bare prefix pattern matches all IDs under that provider`() {
        val resolved = EnrichmentGlobMatcher.resolve(listOf("jira"), ids)
        assertEquals(setOf("jira:tickets", "jira:ticket-keys"), resolved)
    }

    @Test
    fun `explicit glob pattern matches`() {
        val resolved = EnrichmentGlobMatcher.resolve(listOf("gitlab:*"), ids)
        assertEquals(setOf("gitlab:mr"), resolved)
    }

    @Test
    fun `pattern with no matches returns empty set`() {
        val resolved = EnrichmentGlobMatcher.resolve(listOf("github"), ids)
        assertEquals(emptySet(), resolved)
    }

    @Test
    fun `multiple patterns union their matches`() {
        val resolved = EnrichmentGlobMatcher.resolve(listOf("jira", "ci"), ids)
        assertEquals(setOf("jira:tickets", "jira:ticket-keys", "ci:failed-jobs"), resolved)
    }

    @Test
    fun `matchesAny is true when a pattern matches at least one ID`() {
        assertTrue(EnrichmentGlobMatcher.matchesAny("jira", ids))
    }

    @Test
    fun `matchesAny is false when no ID matches`() {
        assertFalse(EnrichmentGlobMatcher.matchesAny("github", ids))
    }

    @Test
    fun `empty patterns or empty ids resolve to nothing`() {
        assertEquals(emptySet(), EnrichmentGlobMatcher.resolve(emptyList(), ids))
        assertEquals(emptySet(), EnrichmentGlobMatcher.resolve(listOf("jira"), emptySet()))
        assertFalse(EnrichmentGlobMatcher.matchesAny("jira", emptySet()))
    }

    @Test
    fun `patternsFor adds each rule binding to the supported patterns once`() {
        val bindings = mapOf("rule-a" to listOf("ci", "jira"), "rule-b" to listOf("gitlab:*"))
        assertEquals(
            listOf("jira", "ci", "gitlab:*"),
            EnrichmentGlobMatcher.patternsFor(listOf("jira"), bindings, listOf("rule-a", "rule-b", "rule-c")),
        )
    }
}
