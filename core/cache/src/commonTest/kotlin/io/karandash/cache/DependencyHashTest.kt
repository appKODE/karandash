package io.karandash.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DependencyHashTest {
    @Test
    fun emptyMapReturnsEmptyHash() {
        assertEquals("0", DependencyHash.compute(emptyMap()))
    }

    @Test
    fun sameEntriesProduceSameHash() {
        val deps = mapOf("title" to "Fix bug", "branch" to "main")
        assertEquals(DependencyHash.compute(deps), DependencyHash.compute(deps))
    }

    @Test
    fun orderDoesNotAffectHash() {
        val a = mapOf("title" to "Fix bug", "branch" to "main")
        val b = mapOf("branch" to "main", "title" to "Fix bug")
        assertEquals(DependencyHash.compute(a), DependencyHash.compute(b))
    }

    @Test
    fun differentValuesProduceDifferentHashes() {
        val a = mapOf("title" to "Fix bug")
        val b = mapOf("title" to "Add feature")
        assertNotEquals(DependencyHash.compute(a), DependencyHash.compute(b))
    }

    @Test
    fun differentKeysProduceDifferentHashes() {
        val a = mapOf("title" to "value")
        val b = mapOf("branch" to "value")
        assertNotEquals(DependencyHash.compute(a), DependencyHash.compute(b))
    }

    @Test
    fun singleEntryProducesNonEmptyHash() {
        val hash = DependencyHash.compute(mapOf("key" to "value"))
        assertNotEquals("0", hash)
        assertTrue(hash.isNotEmpty())
    }

    @Test
    fun lengthPrefixingPreventsAmbiguity() {
        // "ab" + "cd" vs "a" + "bcd" should differ
        val a = mapOf("ab" to "cd")
        val b = mapOf("a" to "bcd")
        assertNotEquals(DependencyHash.compute(a), DependencyHash.compute(b))
    }
}
