package io.karandash.providers.common

import io.karandash.api.exception.KarandashException
import io.karandash.logger.Logger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmallHelpersTest {
    @Test
    fun `merge request id must be an integer`() {
        assertEquals(42, requireMrIdAsInt("42"))
        val error = assertFailsWith<IllegalArgumentException> { requireMrIdAsInt("abc", "pull request ID") }
        assertEquals("Invalid pull request ID: abc", error.message)
    }

    @Test
    fun `a truncated json list is unreadable and a bare scalar is not a list`() {
        val truncated = assertFailsWith<IllegalStateException> { jsonListIds("""{"a":""", "id") }
        assertTrue(truncated.message.orEmpty().startsWith("Unreadable merge request list"), truncated.message)
        assertFailsWith<IllegalStateException> { jsonListIds("42", "id") }
    }

    @Test
    fun `project ids split into two or three parts or fail naming the expected format`() {
        assertEquals("o" to "r", ProjectIdParser.parsePair("o/r"))
        assertEquals(Triple("o", "p", "r"), ProjectIdParser.parseTriple("o/p/r"))
        val pair = assertFailsWith<IllegalArgumentException> { ProjectIdParser.parsePair("o/p/r") }
        assertEquals("Invalid project ID format: expected 'owner/repo', got: o/p/r", pair.message)
        val triple = assertFailsWith<IllegalArgumentException> { ProjectIdParser.parseTriple("o/r", "a/b/c") }
        assertEquals("Invalid project ID format: expected 'a/b/c', got: o/r", triple.message)
    }

    @Test
    fun `remote url parser prefers ssh then https then null`() {
        val ssh = Regex("""git@h:(.+)\.git""")
        val https = Regex("""https://h/(.+)\.git""")

        assertEquals("a/b", GitRemoteUrlParser.parse("git@h:a/b.git", ssh, https))
        assertEquals("c/d", GitRemoteUrlParser.parse("https://h/c/d.git", ssh, https))
        assertNull(GitRemoteUrlParser.parse("ftp://nope", ssh, https))
    }

    @Test
    fun `pagination url must stay under the expected base`() {
        UrlValidator.validateUrlMatchesBase("https://h.io/api/page2", "https://h.io/api/")
        assertFailsWith<IllegalArgumentException> {
            UrlValidator.validateUrlMatchesBase("https://evil.io/api/page2", "https://h.io/api")
        }
    }

    @Test
    fun `markEach counts marked comments and logs the ones that fail`() =
        runTest {
            val warnings = mutableListOf<String>()
            val logger = object : Logger {
                override fun debug(message: String) = Unit

                override fun info(message: String) = Unit

                override fun warn(message: String) {
                    warnings += message
                }

                override fun error(message: String) = Unit
            }
            val marked = mutableListOf<Int>()

            val count = SupersededInlineComment.markEach(listOf(1, 2, 3), logger) {
                if (it == 2) throw KarandashException("gone")
                marked += it
            }

            assertEquals(2, count)
            assertEquals(listOf(1, 3), marked)
            assertEquals(listOf("Could not mark an earlier inline comment as superseded: gone"), warnings)
        }

    @Test
    fun `inScope accepts everything without a filter and only listed paths with one`() {
        assertTrue(SupersededInlineComment.inScope("a.kt", null))
        assertTrue(SupersededInlineComment.inScope("a.kt", setOf("a.kt")))
        assertTrue(!SupersededInlineComment.inScope("b.kt", setOf("a.kt")))
        assertTrue(!SupersededInlineComment.inScope(null, setOf("a.kt")))
    }
}
