package io.karandash.cli.commands.stats.audit

import io.karandash.cli.commands.stats.audit.AuditFixtures.botNote
import kotlin.test.Test
import kotlin.test.assertEquals

class ClusterMatcherTest {
    @Test
    fun `normalize strips marker markdown and case`() {
        val body = "**Null** _safety_:   `load()`\n<!-- karandash-finding {\"rule\":\"x\"} -->"
        assertEquals("null safety: load()", ClusterMatcher.normalize(body))
    }

    @Test
    fun `near duplicate on same path joins one cluster`() {
        val notes = listOf(botNote(1, "load() may return null here"), botNote(2, "**load() may return null here!**"))
        assertEquals(listOf(listOf(1L, 2L)), ClusterMatcher.cluster(notes).map { c -> c.notes.map { it.id } })
    }

    @Test
    fun `different message or path stays separate`() {
        val notes = listOf(
            botNote(1, "load() may return null here"),
            botNote(2, "magic number 42 should be a constant"),
            botNote(3, "load() may return null here", path = "src/Other.kt"),
        )
        assertEquals(3, ClusterMatcher.cluster(notes).size)
    }

    @Test
    fun `rule id comes from the karandash marker`() {
        val body = "msg\n<!-- karandash-finding {\"rule\":\"null-safety\",\"hash\":1} -->"
        assertEquals("null-safety", ClusterMatcher.ruleOf(body))
    }

    @Test
    fun `rule id falls back to a leading kebab id then unknown`() {
        assertEquals("magic-number", ClusterMatcher.ruleOf("**magic-number**: use a constant"))
        assertEquals("unknown", ClusterMatcher.ruleOf("Use a constant here"))
    }

    @Test
    fun `jaccard of token sets`() {
        assertEquals(0.5, ClusterMatcher.jaccard(setOf("a", "b"), setOf("b", "c", "a", "d")))
        assertEquals(1.0, ClusterMatcher.jaccard(emptySet(), emptySet()))
    }
}
