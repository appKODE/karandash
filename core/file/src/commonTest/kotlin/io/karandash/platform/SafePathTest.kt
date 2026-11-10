package io.karandash.platform

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SafePathTest {
    @Test
    fun `unixParent matches dirname semantics`() {
        assertEquals("a/b", unixParent("a/b/c"))
        assertEquals("/", unixParent("/a"))
        assertEquals("/a", unixParent("/a//b"))
        assertEquals(null, unixParent("/"))
        assertEquals(null, unixParent("a"))
        assertEquals(null, unixParent(""))
    }

    @Test
    fun `unixName is the last segment`() {
        assertEquals("c.txt", unixName("a/b/c.txt"))
        assertEquals("a", unixName("/a"))
        assertEquals("a", unixName("a"))
        assertEquals("", unixName("/"))
        assertEquals("", unixName(""))
    }

    @Test
    fun `safeParent and safeName agree with kotlinx-io`() {
        for (raw in listOf("a/b/c.txt", "/a/b", "/a", "a", "")) {
            val path = Path(raw)
            assertEquals(path.parent, path.safeParent, "parent of '$raw'")
            assertEquals(path.name, path.safeName, "name of '$raw'")
        }
    }
}
