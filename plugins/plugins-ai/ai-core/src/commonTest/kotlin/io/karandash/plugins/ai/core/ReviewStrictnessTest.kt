package io.karandash.plugins.ai.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReviewStrictnessTest {
    @Test
    fun `fromString parses light`() {
        assertEquals(ReviewStrictness.LIGHT, ReviewStrictness.fromString("light"))
        assertEquals(ReviewStrictness.LIGHT, ReviewStrictness.fromString("LIGHT"))
    }

    @Test
    fun `fromString parses standard`() {
        assertEquals(ReviewStrictness.STANDARD, ReviewStrictness.fromString("standard"))
    }

    @Test
    fun `fromString parses strict`() {
        assertEquals(ReviewStrictness.STRICT, ReviewStrictness.fromString("strict"))
        assertEquals(ReviewStrictness.STRICT, ReviewStrictness.fromString("STRICT"))
    }

    @Test
    fun `fromString defaults to STANDARD for unknown values`() {
        assertEquals(ReviewStrictness.STANDARD, ReviewStrictness.fromString("unknown"))
        assertEquals(ReviewStrictness.STANDARD, ReviewStrictness.fromString(""))
    }

    @Test
    fun `LIGHT limits maxComments to 3`() {
        assertEquals(3, ReviewStrictness.LIGHT.effectiveMaxComments(10))
        assertEquals(2, ReviewStrictness.LIGHT.effectiveMaxComments(2))
    }

    @Test
    fun `STANDARD preserves configMax`() {
        assertEquals(10, ReviewStrictness.STANDARD.effectiveMaxComments(10))
        assertEquals(5, ReviewStrictness.STANDARD.effectiveMaxComments(5))
    }

    @Test
    fun `STRICT increases maxComments to at least 20`() {
        assertEquals(20, ReviewStrictness.STRICT.effectiveMaxComments(10))
        assertEquals(25, ReviewStrictness.STRICT.effectiveMaxComments(25))
    }

    @Test
    fun `LIGHT has prompt overlay`() {
        assertNotNull(ReviewStrictness.LIGHT.promptOverlay())
    }

    @Test
    fun `STANDARD has no prompt overlay`() {
        assertNull(ReviewStrictness.STANDARD.promptOverlay())
    }

    @Test
    fun `STRICT has prompt overlay`() {
        assertNotNull(ReviewStrictness.STRICT.promptOverlay())
    }
}
