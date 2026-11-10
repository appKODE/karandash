package io.karandash.providers.common

import io.karandash.sdk.Logger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupersededInlineCommentTest {
    private val marker = "<!-- karandash-inline -->"

    @Test
    fun `a live marked comment is pending and gets the superseded note on top`() {
        val body = "$marker\nUnchecked read"

        assertTrue(SupersededInlineComment.isPending(body, marker))
        val edited = SupersededInlineComment.body(body)
        assertTrue(edited.startsWith(SupersededInlineComment.NOTE))
        assertTrue(edited.endsWith(body), "the original text and marker stay, so dismissals are still recognised")
    }

    @Test
    fun `an already superseded comment is not pending again`() {
        assertFalse(SupersededInlineComment.isPending(SupersededInlineComment.body("$marker\nx"), marker))
    }

    @Test
    fun `an unmarked comment is not pending`() {
        assertFalse(SupersededInlineComment.isPending("a reviewer's comment", marker))
        assertFalse(SupersededInlineComment.isPending(null, marker))
    }

    @Test
    fun `path filter keeps every file when no files are given`() {
        assertTrue(SupersededInlineComment.inScope("A.kt", null))
        assertTrue(SupersededInlineComment.inScope("A.kt", setOf("A.kt")))
        assertFalse(SupersededInlineComment.inScope("B.kt", setOf("A.kt")))
        assertFalse(SupersededInlineComment.inScope(null, setOf("A.kt")))
        assertEquals(true, SupersededInlineComment.inScope(null, null))
    }

    @Test
    fun `a client error on one comment marks the rest and warns once`() =
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
            val marked = SupersededInlineComment.markEach(listOf(1, 2, 3), logger) { if (it == 2) error("HTTP 500 updating note") }
            assertEquals(2, marked)
            assertEquals(1, warnings.size, warnings.toString())
        }
}
