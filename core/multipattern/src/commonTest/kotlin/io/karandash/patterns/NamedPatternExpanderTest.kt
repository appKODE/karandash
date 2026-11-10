package io.karandash.patterns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NamedPatternExpanderTest {
    private val registry = CorePatternRegistry.create()

    // ------------------------------------------------------------------
    // Basic expansion
    // ------------------------------------------------------------------

    @Test
    fun `expands simple no-arg pattern`() {
        assertEquals("[a-z][a-z0-9]*(-[a-z0-9]+)*", expand("@kebab-case"))
    }

    @Test
    fun `expands camel-case pattern`() {
        assertEquals("[a-z][a-zA-Z0-9]*", expand("@camel-case"))
    }

    // ------------------------------------------------------------------
    // Parameterized patterns (positional)
    // ------------------------------------------------------------------

    @Test
    fun `expands optional with positional arg`() {
        val result = expand("@optional(foo)")
        assertEquals("(?:foo)?", result)
    }

    @Test
    fun `expands bracketed with positional arg`() {
        val result = expand("@bracketed(\\d+)")
        assertEquals("\\[\\d+\\]", result)
    }

    @Test
    fun `expands one-or-more with positional arg`() {
        val result = expand("@one-or-more(ab)")
        assertEquals("(?:ab)+", result)
    }

    @Test
    fun `expands separated-list with two positional args`() {
        val result = expand("@separated-list(\\w+, \\|)")
        assertEquals("\\w+(?:\\|\\w+)*", result)
    }

    @Test
    fun `separated-list keeps a quoted separator whole`() {
        assertEquals("\\w+(?:, \\w+)*", expand("@separated-list(\\w+, ', ')"))
        assertEquals("\\w+(?:, \\w+)*", expand("@separated-list(@pattern:\\w+, @separator:\", \")"))
    }

    @Test
    fun `quoted separator list matches comma separated tickets`() {
        val regex = Regex(NamedPatternExpander.resolveInnerReferences(registry, "^T: @separated-list([A-Z]+-\\d+, ', ')$", emptySet(), 0))
        assertTrue(regex.matches("T: BRAVO-1, BRAVO-2"))
        assertTrue(!regex.matches("T: BRAVO-1'BRAVO-2"))
    }

    // ------------------------------------------------------------------
    // Named args
    // ------------------------------------------------------------------

    @Test
    fun `expands optional with named arg`() {
        val result = expand("@optional(@pattern:foo)")
        assertEquals("(?:foo)?", result)
    }

    @Test
    fun `expands separated-list with named args`() {
        val result = expand("@separated-list(@pattern:\\w+, @separator:\\|)")
        assertEquals("\\w+(?:\\|\\w+)*", result)
    }

    // ------------------------------------------------------------------
    // Inner @-reference resolution
    // ------------------------------------------------------------------

    @Test
    fun `resolves inner references in expanded pattern`() {
        // Build a registry that includes all core patterns plus a custom one
        val customRegistry = CorePatternRegistry.create()
        customRegistry.register(
            PatternDefinition(
                name = "my-opt-kebab",
                params = emptyList(),
                expand = { "(?:@kebab-case)?" },
            ),
        )
        // @kebab-case inside the expanded string should itself be resolved
        val result = NamedPatternExpander.expand(customRegistry, "@my-opt-kebab")
        assertEquals("(?:[a-z][a-z0-9]*(-[a-z0-9]+)*)?", result)
    }

    @Test
    fun `an at-sign naming no pattern inside a body stays literal`() {
        val customRegistry = CorePatternRegistry.create()
        customRegistry.register(PatternDefinition(name = "bot-author", params = emptyList(), expand = { "renovate@bot\\.example" }))
        customRegistry.register(PatternDefinition(name = "bot-or-kebab", params = emptyList(), expand = { "x@bot|@kebab-case" }))

        assertEquals("renovate@bot\\.example", NamedPatternExpander.expand(customRegistry, "@bot-author"))
        assertEquals("x@bot|[a-z][a-z0-9]*(-[a-z0-9]+)*", NamedPatternExpander.expand(customRegistry, "@bot-or-kebab"))
        assertFailsWith<IllegalArgumentException> { NamedPatternExpander.expand(customRegistry, "@kebab-cse") }
    }

    // ------------------------------------------------------------------
    // Circular reference detection
    // ------------------------------------------------------------------

    @Test
    fun `detects direct circular reference`() {
        val r = DefaultPatternRegistry()
        r.register(
            PatternDefinition(name = "loop", params = emptyList(), expand = { "@loop" }),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            NamedPatternExpander.expand(r, "@loop")
        }
        assertTrue(ex.message?.contains("Circular") == true, "Expected 'Circular' in: ${ex.message}")
    }

    @Test
    fun `detects indirect circular reference`() {
        val r = DefaultPatternRegistry()
        r.register(PatternDefinition(name = "a", params = emptyList(), expand = { "@b" }))
        r.register(PatternDefinition(name = "b", params = emptyList(), expand = { "@a" }))
        val ex = assertFailsWith<IllegalArgumentException> {
            NamedPatternExpander.expand(r, "@a")
        }
        assertTrue(ex.message?.contains("Circular") == true, "Expected 'Circular' in: ${ex.message}")
    }

    // ------------------------------------------------------------------
    // Max depth
    // ------------------------------------------------------------------

    @Test
    fun `reports error when max depth exceeded`() {
        // Chain of 25 patterns - deeper than MAX_DEPTH (20)
        val r = DefaultPatternRegistry()
        val chainLength = NamedPatternExpander.MAX_DEPTH + 5
        for (i in 0 until chainLength) {
            val next = if (i + 1 < chainLength) "@chain${i + 1}" else "end"
            r.register(PatternDefinition(name = "chain$i", params = emptyList(), expand = { next }))
        }
        val ex = assertFailsWith<IllegalArgumentException> {
            NamedPatternExpander.expand(r, "@chain0")
        }
        assertTrue(
            ex.message?.contains("depth limit") == true || ex.message?.contains("depth") == true,
            "Expected depth message in: ${ex.message}",
        )
    }

    @Test
    fun `installed max depth bounds expansion until cleared`() {
        val r = DefaultPatternRegistry()
        for (i in 0 until 8) {
            val next = if (i + 1 < 8) "@chain${i + 1}" else "end"
            r.register(PatternDefinition(name = "chain$i", params = emptyList(), expand = { next }))
        }
        assertEquals("end", NamedPatternExpander.expand(r, "@chain0"))
        NamedPatternExpander.installMaxDepth(5)
        try {
            assertEquals(5, NamedPatternExpander.maxDepth)
            assertFailsWith<IllegalArgumentException> { NamedPatternExpander.expand(r, "@chain0") }
        } finally {
            NamedPatternExpander.clearMaxDepth(5)
        }
        assertEquals(NamedPatternExpander.MAX_DEPTH, NamedPatternExpander.maxDepth)
    }

    // ------------------------------------------------------------------
    // Unknown pattern
    // ------------------------------------------------------------------

    @Test
    fun `unknown pattern throws IllegalArgumentException`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            NamedPatternExpander.expand(registry, "@no-such-pattern")
        }
        assertTrue(ex.message?.contains("no-such-pattern") == true)
    }

    // ------------------------------------------------------------------
    // extractName helper
    // ------------------------------------------------------------------

    @Test
    fun `extractName strips leading at-sign`() {
        assertEquals("kebab-case", NamedPatternExpander.extractName("@kebab-case"))
    }

    @Test
    fun `extractName strips args`() {
        assertEquals("optional", NamedPatternExpander.extractName("@optional(foo)"))
    }

    // ------------------------------------------------------------------
    // resolveInnerReferences - no @ means passthrough
    // ------------------------------------------------------------------

    @Test
    fun `resolveInnerReferences returns input unchanged when no at-signs present`() {
        val result = NamedPatternExpander.resolveInnerReferences(registry, "plain-regex", emptySet(), 0)
        assertEquals("plain-regex", result)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun expand(pattern: String): String = NamedPatternExpander.expand(registry, pattern)
}
