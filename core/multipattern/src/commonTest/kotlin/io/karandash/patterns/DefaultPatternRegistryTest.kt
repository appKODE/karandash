package io.karandash.patterns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultPatternRegistryTest {
    private val registry = DefaultPatternRegistry()

    @Test
    fun `register and exists`() {
        assertFalse(registry.exists("my-pattern"))
        registry.register(simple("my-pattern", "^hello$"))
        assertTrue(registry.exists("my-pattern"))
    }

    @Test
    fun `resolve returns expanded string`() {
        registry.register(simple("greeting", "^hello$"))
        assertEquals("^hello$", registry.resolve("greeting"))
    }

    @Test
    fun `resolve returns null for unknown name`() {
        assertNull(registry.resolve("no-such-pattern"))
    }

    @Test
    fun `register replaces existing definition`() {
        registry.register(simple("p", "first"))
        registry.register(simple("p", "second"))
        assertEquals("second", registry.resolve("p"))
    }

    @Test
    fun `all returns snapshot of registered definitions`() {
        registry.register(simple("a", "regex-a"))
        registry.register(simple("b", "regex-b"))
        val names = registry.all().map { it.name }.toSet()
        assertTrue("a" in names)
        assertTrue("b" in names)
    }

    @Test
    fun `resolve applies caller params`() {
        registry.register(
            PatternDefinition(
                name = "wrap",
                params = listOf(PatternParam(name = "inner")),
                expand = { params -> "\\[${params["inner"]}\\]" },
            ),
        )
        assertEquals("\\[foo\\]", registry.resolve("wrap", mapOf("inner" to "foo")))
    }

    @Test
    fun `resolve applies default param when caller omits it`() {
        registry.register(
            PatternDefinition(
                name = "sep",
                params = listOf(PatternParam(name = "sep", defaultValue = ",")),
                expand = { params -> "a${params["sep"]}b" },
            ),
        )
        assertEquals("a,b", registry.resolve("sep"))
        assertEquals("a|b", registry.resolve("sep", mapOf("sep" to "|")))
    }

    @Test
    fun `all returns immutable snapshot`() {
        registry.register(simple("x", "x"))
        val snapshot = registry.all()
        registry.register(simple("y", "y"))
        // Snapshot taken before registering "y" should not contain "y"
        assertFalse(snapshot.any { it.name == "y" })
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    fun `validate returns null for valid regex`() {
        assertNull(registry.validate("^[a-z]+$"))
    }

    @Test
    fun `validate returns null for valid regex with prefix`() {
        assertNull(registry.validate("regex:^[a-z]+$"))
    }

    @Test
    fun `validate returns error for invalid regex`() {
        val error = registry.validate("[unclosed")
        assertNotNull(error)
        assertTrue(error.contains("Invalid"), "Expected 'Invalid' in: $error")
    }

    @Test
    fun `validate strips glob prefix before checking`() {
        // "*.kt" as a raw regex is invalid, but after stripping "glob:" prefix
        // the remaining "*.kt" is also an invalid regex - that is expected.
        // A well-formed bare regex should pass.
        assertNull(registry.validate("glob:[a-z]+"))
    }

    @Test
    fun `validate strips at-sign prefix before checking`() {
        // "@kebab-case" stripped to "kebab-case" which is a valid regex (literal)
        assertNull(registry.validate("@kebab-case"))
    }

    // Helper
    private fun simple(
        name: String,
        regex: String
    ) = PatternDefinition(name = name, params = emptyList(), expand = { regex })
}
