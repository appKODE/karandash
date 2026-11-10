package io.karandash.utils.suggestion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PatternExampleEscapesTest {
    @Test
    fun `escaped characters become literals and classes become placeholders`() {
        assertEquals("a.b  ...", PatternExampleGenerator.generateExample("^a\\.b\\s"))
        assertEquals("a   ...", PatternExampleGenerator.generateExample("^a\\s\\s"))
        assertEquals("v123 ...", PatternExampleGenerator.generateExample("^v\\d+"))
        assertEquals("word ...", PatternExampleGenerator.generateExample("^\\w+"))
    }

    @Test
    fun `a trailing backslash ends the prefix`() {
        assertEquals("ab ...", PatternExampleGenerator.generateExample("ab\\"))
    }

    @Test
    fun `groups give their first alternative and non-capturing prefixes are skipped`() {
        assertEquals("fix:  ...", PatternExampleGenerator.generateExample("^(?:fix|feat): "))
        assertEquals("a(b|c)d:  ...", PatternExampleGenerator.generateExample("^(a(b|c)d|x): "))
        assertEquals("a\\.b:  ...", PatternExampleGenerator.generateExample("^(a\\.b|x): "))
    }

    @Test
    fun `an unclosed group or empty alternative gives no further prefix`() {
        assertEquals("ab ...", PatternExampleGenerator.generateExample("ab(c|d"))
        assertNull(PatternExampleGenerator.generateExample("(c"))
        assertNull(PatternExampleGenerator.generateExample("[a-z]+"))
    }
}
