package io.karandash.plugins.ai.dispatch

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MrMapTest {
    private val all = listOf(change("A.kt", 3, 1), change("B.kt", 5, 0), change("C.kt", 0, 2))

    @Test
    fun `a chunk is told the files of the other chunks and not its own`() {
        val content = checkNotNull(MrMap.block(all.take(1), all, maxFiles = 50)).content

        assertTrue("- B.kt (+5 -0)" in content)
        assertTrue("- C.kt (+0 -2)" in content)
        assertFalse("A.kt" in content)
    }

    @Test
    fun `the map lists at most max files and counts the rest`() {
        val content = checkNotNull(MrMap.block(all.take(1), all, maxFiles = 1)).content

        assertTrue("- B.kt" in content)
        assertFalse("- C.kt" in content)
        assertTrue("and 1 more files" in content)
    }

    @Test
    fun `zero max files or no other files sends no map`() {
        assertNull(MrMap.block(all.take(1), all, maxFiles = 0))
        assertNull(MrMap.block(all, all, maxFiles = 50))
    }

    @Test
    fun `the map is one context block of the chunk call`() {
        val blocks = MrMap.block(all.take(2), all, maxFiles = 50)

        assertEquals(MrMap.SOURCE, blocks?.source)
    }

    @Test
    fun `a file split across chunks is labelled part k of n in each chunk`() {
        val a1 = change("A.kt", 1, 0)
        val chunks = listOf(listOf(a1, change("B.kt", 1, 0)), listOf(change("A.kt", 2, 0)), listOf(change("C.kt", 1, 0)))

        assertEquals(mapOf("A.kt" to "part 1/2"), MrMap.parts(chunks, 0))
        assertEquals(mapOf("A.kt" to "part 2/2"), MrMap.parts(chunks, 1))
        assertEquals(emptyMap(), MrMap.parts(chunks, 2))
    }

    @Test
    fun `a split file is listed with its part and that other hunks are reviewed in another call`() {
        val content = checkNotNull(MrMap.block(all, all, maxFiles = 50, parts = mapOf("A.kt" to "part 1/2"))).content

        assertTrue("- A.kt (part 1/2; other hunks are reviewed in another call)" in content, content)
    }

    private fun change(
        path: String,
        added: Int,
        removed: Int,
    ) = FileChange(path = path, changeType = ChangeType.MODIFIED, additions = added, deletions = removed)
}
