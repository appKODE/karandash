package io.karandash.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectionMatcherTest {
    @Test
    fun `hasAll returns true when all required items are present`() {
        assertTrue(CollectionMatcher.hasAll(listOf("a", "b", "c"), listOf("a", "c")))
    }

    @Test
    fun `hasAll returns false when a required item is missing`() {
        assertFalse(CollectionMatcher.hasAll(listOf("a", "b"), listOf("a", "c")))
    }

    @Test
    fun `hasAll returns true for empty required list`() {
        assertTrue(CollectionMatcher.hasAll(listOf("a"), emptyList()))
    }

    @Test
    fun `hasAny returns true when at least one candidate is present`() {
        assertTrue(CollectionMatcher.hasAny(listOf("a", "b"), listOf("b", "c")))
    }

    @Test
    fun `hasAny returns false when no candidates are present`() {
        assertFalse(CollectionMatcher.hasAny(listOf("a", "b"), listOf("c", "d")))
    }

    @Test
    fun `hasAny returns false for empty candidates`() {
        assertFalse(CollectionMatcher.hasAny(listOf("a"), emptyList()))
    }

    @Test
    fun `findMissing returns missing items`() {
        assertEquals(listOf("c"), CollectionMatcher.findMissing(listOf("a", "b"), listOf("a", "c")))
    }

    @Test
    fun `findMissing returns empty when all present`() {
        assertEquals(emptyList(), CollectionMatcher.findMissing(listOf("a", "b"), listOf("a", "b")))
    }

    @Test
    fun `findPresent returns items that exist in collection`() {
        assertEquals(listOf("a"), CollectionMatcher.findPresent(listOf("a", "b"), listOf("a", "c")))
    }

    @Test
    fun `findPresent returns empty when none present`() {
        assertEquals(emptyList(), CollectionMatcher.findPresent(listOf("a", "b"), listOf("c", "d")))
    }

    @Test
    fun `findPresent returns all when all present`() {
        assertEquals(listOf("a", "b"), CollectionMatcher.findPresent(listOf("a", "b", "c"), listOf("a", "b")))
    }

    @Test
    fun `methods accept Set as items parameter`() {
        val items = setOf("a", "b", "c")
        assertTrue(CollectionMatcher.hasAll(items, listOf("a", "b")))
        assertTrue(CollectionMatcher.hasAny(items, listOf("b", "d")))
        assertEquals(listOf("d"), CollectionMatcher.findMissing(items, listOf("a", "d")))
        assertEquals(listOf("a"), CollectionMatcher.findPresent(items, listOf("a", "d")))
    }
}
