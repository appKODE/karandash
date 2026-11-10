package io.karandash.utils.mr

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiffHelpersTest {
    @Test
    fun `addedLines extracts lines starting with plus`() {
        val diffContent = """
            diff --git a/file.kt b/file.kt
            --- a/file.kt
            +++ b/file.kt
            @@ -1,3 +1,4 @@
             fun main() {
            +    println("Hello")
            +    println("World")
             }
        """.trimIndent()

        val fileChange = FileChange("file.kt", ChangeType.MODIFIED, diffContent = diffContent)
        val added = fileChange.addedLines()

        assertEquals(2, added.size)
        assertEquals("    println(\"Hello\")", added[0])
        assertEquals("    println(\"World\")", added[1])
    }

    @Test
    fun `addedLines excludes file header with plus plus plus`() {
        val diffContent = """
            diff --git a/file.kt b/file.kt
            --- a/file.kt
            +++ b/file.kt
            @@ -1,3 +1,4 @@
            +fun newFunction() {}
        """.trimIndent()

        val fileChange = FileChange("file.kt", ChangeType.MODIFIED, diffContent = diffContent)
        val added = fileChange.addedLines()

        assertEquals(1, added.size)
        assertEquals("fun newFunction() {}", added[0])
    }

    @Test
    fun `addedLines returns empty list when no additions`() {
        val diffContent = """
            diff --git a/file.kt b/file.kt
            --- a/file.kt
            +++ b/file.kt
            @@ -1,3 +1,2 @@
             fun main() {
            -    println("Old")
             }
        """.trimIndent()

        val fileChange = FileChange("file.kt", ChangeType.MODIFIED, diffContent = diffContent)
        val added = fileChange.addedLines()

        assertEquals(0, added.size)
    }

    @Test
    fun `addedLines returns empty list when diffContent is null`() {
        val fileChange = FileChange("file.kt", ChangeType.MODIFIED, diffContent = null)
        val added = fileChange.addedLines()

        assertEquals(0, added.size)
    }

    @Test
    fun `removedLines extracts lines starting with minus`() {
        val diffContent = """
            diff --git a/file.kt b/file.kt
            --- a/file.kt
            +++ b/file.kt
            @@ -1,4 +1,2 @@
             fun main() {
            -    println("Remove this")
            -    println("And this")
             }
        """.trimIndent()

        val fileChange = FileChange("file.kt", ChangeType.MODIFIED, diffContent = diffContent)
        val removed = fileChange.removedLines()

        assertEquals(2, removed.size)
        assertEquals("    println(\"Remove this\")", removed[0])
        assertEquals("    println(\"And this\")", removed[1])
    }

    @Test
    fun `removedLines excludes file header with minus minus minus`() {
        val diffContent = """
            diff --git a/file.kt b/file.kt
            --- a/file.kt
            +++ b/file.kt
            @@ -1,3 +1,2 @@
            -fun oldFunction() {}
        """.trimIndent()

        val fileChange = FileChange("file.kt", ChangeType.MODIFIED, diffContent = diffContent)
        val removed = fileChange.removedLines()

        assertEquals(1, removed.size)
        assertEquals("fun oldFunction() {}", removed[0])
    }

    @Test
    fun `removedLines returns empty list when no deletions`() {
        val diffContent = """
            diff --git a/file.kt b/file.kt
            --- a/file.kt
            +++ b/file.kt
            @@ -1,2 +1,3 @@
             fun main() {
            +    println("New")
             }
        """.trimIndent()

        val fileChange = FileChange("file.kt", ChangeType.MODIFIED, diffContent = diffContent)
        val removed = fileChange.removedLines()

        assertEquals(0, removed.size)
    }

    @Test
    fun `removedLines returns empty list when diffContent is null`() {
        val fileChange = FileChange("file.kt", ChangeType.MODIFIED, diffContent = null)
        val removed = fileChange.removedLines()

        assertEquals(0, removed.size)
    }

    @Test
    fun `hasContentMatching returns true when pattern matches`() {
        val diffContent = """
            diff --git a/file.kt b/file.kt
            +++ b/file.kt
            +    console.log("debug")
            +    val secret = "password123"
        """.trimIndent()

        val fileChange = FileChange("file.kt", ChangeType.MODIFIED, diffContent = diffContent)

        assertTrue(fileChange.hasContentMatching(Regex("console\\.log")))
        assertTrue(fileChange.hasContentMatching(Regex("secret")))
        assertTrue(fileChange.hasContentMatching(Regex("password\\d+")))
    }

    @Test
    fun `hasContentMatching returns false when pattern does not match`() {
        val diffContent = """
            diff --git a/file.kt b/file.kt
            +++ b/file.kt
            +    println("Hello")
        """.trimIndent()

        val fileChange = FileChange("file.kt", ChangeType.MODIFIED, diffContent = diffContent)

        assertFalse(fileChange.hasContentMatching(Regex("console\\.log")))
        assertFalse(fileChange.hasContentMatching(Regex("secret")))
    }

    @Test
    fun `hasContentMatching returns false when diffContent is null`() {
        val fileChange = FileChange("file.kt", ChangeType.MODIFIED, diffContent = null)

        assertFalse(fileChange.hasContentMatching(Regex("anything")))
    }

    @Test
    fun `addedLinesWithNumbers returns correct line numbers for single hunk`() {
        val diffContent = """
            diff --git a/file.kt b/file.kt
            --- a/file.kt
            +++ b/file.kt
            @@ -1,3 +1,5 @@
             fun main() {
            +    println("Hello")
            +    println("World")
                 val x = 1
             }
        """.trimIndent()

        val fileChange = FileChange("file.kt", ChangeType.MODIFIED, diffContent = diffContent)
        val added = fileChange.addedLinesWithNumbers()

        assertEquals(2, added.size)
        assertEquals(DiffLineInfo(2, "    println(\"Hello\")"), added[0])
        assertEquals(DiffLineInfo(3, "    println(\"World\")"), added[1])
    }

    @Test
    fun `addedLinesWithNumbers handles multiple hunks`() {
        val diffContent = """
            diff --git a/file.kt b/file.kt
            --- a/file.kt
            +++ b/file.kt
            @@ -1,3 +1,4 @@
             fun main() {
            +    println("First")
                 val x = 1
             }
            @@ -10,3 +11,4 @@
             fun other() {
            +    println("Second")
                 val y = 2
             }
        """.trimIndent()

        val fileChange = FileChange("file.kt", ChangeType.MODIFIED, diffContent = diffContent)
        val added = fileChange.addedLinesWithNumbers()

        assertEquals(2, added.size)
        assertEquals(DiffLineInfo(2, "    println(\"First\")"), added[0])
        assertEquals(DiffLineInfo(12, "    println(\"Second\")"), added[1])
    }

    @Test
    fun `addedLinesWithNumbers returns empty list for null diffContent`() {
        val fileChange = FileChange("file.kt", ChangeType.MODIFIED, diffContent = null)
        val added = fileChange.addedLinesWithNumbers()

        assertTrue(added.isEmpty())
    }

    @Test
    fun `addedLinesWithNumbers returns empty list for diff with no hunks`() {
        val diffContent = """
            diff --git a/file.kt b/file.kt
            --- a/file.kt
            +++ b/file.kt
        """.trimIndent()

        val fileChange = FileChange("file.kt", ChangeType.MODIFIED, diffContent = diffContent)
        val added = fileChange.addedLinesWithNumbers()

        assertTrue(added.isEmpty())
    }

    @Test
    fun `addedLinesWithNumbers skips removed lines and tracks line numbers correctly`() {
        val diffContent = """
            diff --git a/file.kt b/file.kt
            --- a/file.kt
            +++ b/file.kt
            @@ -1,4 +1,4 @@
             fun main() {
            -    val old = 1
            +    val new = 2
                 println(x)
             }
        """.trimIndent()

        val fileChange = FileChange("file.kt", ChangeType.MODIFIED, diffContent = diffContent)
        val added = fileChange.addedLinesWithNumbers()

        assertEquals(1, added.size)
        assertEquals(DiffLineInfo(2, "    val new = 2"), added[0])
    }

    @Test
    fun `addedLinesWithNumbers returns empty for context-only diff`() {
        val diffContent = """
            diff --git a/file.kt b/file.kt
            --- a/file.kt
            +++ b/file.kt
            @@ -1,3 +1,3 @@
             fun main() {
                 val x = 1
             }
        """.trimIndent()

        val fileChange = FileChange("file.kt", ChangeType.MODIFIED, diffContent = diffContent)
        val added = fileChange.addedLinesWithNumbers()

        assertTrue(added.isEmpty())
    }

    @Test
    fun `addedLines and removedLines work together on complex diff`() {
        val diffContent = """
            diff --git a/Main.kt b/Main.kt
            --- a/Main.kt
            +++ b/Main.kt
            @@ -1,5 +1,6 @@
             fun main() {
            -    val oldVar = 1
            +    val newVar = 2
            +    println(newVar)
                 // unchanged line
            -    println(oldVar)
             }
        """.trimIndent()

        val fileChange = FileChange("Main.kt", ChangeType.MODIFIED, diffContent = diffContent)

        val added = fileChange.addedLines()
        assertEquals(2, added.size)
        assertEquals("    val newVar = 2", added[0])
        assertEquals("    println(newVar)", added[1])

        val removed = fileChange.removedLines()
        assertEquals(2, removed.size)
        assertEquals("    val oldVar = 1", removed[0])
        assertEquals("    println(oldVar)", removed[1])
    }

    @Test
    fun `addedLinesWithNumbers keeps added lines starting with plus plus and counts past removed minus minus lines`() {
        val diffContent = """
            --- a/file.c
            +++ b/file.c
            @@ -1,3 +1,3 @@
             int x = 0;
            ---x;
            +++x;
             return x;
            +done();
        """.trimIndent()

        val added = FileChange("file.c", ChangeType.MODIFIED, diffContent = diffContent).addedLinesWithNumbers()

        assertEquals(listOf(DiffLineInfo(2, "++x;"), DiffLineInfo(4, "done();")), added)
    }

    @Test
    fun `addedLines and removedLines keep lines starting with plus plus and minus minus inside a hunk`() {
        val diffContent = """
            --- a/file.c
            +++ b/file.c
            @@ -1,3 +1,3 @@
             int x = 0;
            ---x;
            +++x;
             return x;
        """.trimIndent()
        val change = FileChange("file.c", ChangeType.MODIFIED, diffContent = diffContent)

        assertEquals(listOf("++x;"), change.addedLines())
        assertEquals(listOf("--x;"), change.removedLines())
    }

    @Test
    fun `addedLinesWithNumbers ignores a hunk header quoted inside an added line`() {
        val diffContent = """
            @@ -1,1 +1,3 @@
             ctx
            +// see @@ -1 +9 @@
            +val y = 2
        """.trimIndent()

        val added = FileChange("file.kt", ChangeType.MODIFIED, diffContent = diffContent).addedLinesWithNumbers()

        assertEquals(listOf(DiffLineInfo(2, "// see @@ -1 +9 @@"), DiffLineInfo(3, "val y = 2")), added)
    }

    @Test
    fun `oldLineAt counts old lines the way GitLab line codes do`() {
        val diffContent = listOf(
            "--- a/file.kt",
            "+++ b/file.kt",
            "@@ -10,4 +10,4 @@",
            " keep",
            "+added",
            " kept",
            "-removed",
            " tail",
            "@@ -40,1 +40,2 @@",
            " last",
            "+appended",
            "\\ No newline at end of file",
        ).joinToString("\n")
        val change = FileChange("file.kt", ChangeType.MODIFIED, diffContent = diffContent)

        assertEquals(10, change.oldLineAt(10))
        assertEquals(11, change.oldLineAt(11))
        assertEquals(11, change.oldLineAt(12))
        assertEquals(13, change.oldLineAt(13))
        assertEquals(41, change.oldLineAt(41))
        assertEquals(null, change.oldLineAt(25))
        assertEquals(null, FileChange("x.kt", ChangeType.ADDED).oldLineAt(1))
    }

    @Test
    fun `oldLineAt of a new file is zero`() {
        val diffContent = "@@ -0,0 +1,2 @@\n+one\n+two"
        val change = FileChange("new.kt", ChangeType.ADDED, diffContent = diffContent)

        assertEquals(0, change.oldLineAt(1))
        assertEquals(0, change.oldLineAt(2))
    }
}
