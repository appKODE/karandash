package io.karandash.testfixtures

import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestFilesTest {
    private val dir = TestFiles.createTestTempDirectory("test-files")

    @AfterTest
    fun cleanup() = TestFiles.deleteRecursively(dir)

    @Test
    fun `temp directories exist and are unique`() {
        val other = TestFiles.createTestTempDirectory("test-files")
        try {
            assertTrue(TestFiles.exists(dir))
            assertNotEquals(dir, other)
        } finally {
            TestFiles.deleteRecursively(other)
        }
    }

    @Test
    fun `writeText creates parent directories and readText round trips utf8`() {
        val file = Path(dir, "a/b/c.txt")

        TestFiles.writeText(file, "привет\n")

        assertEquals("привет\n", TestFiles.readText(file))
    }

    @Test
    fun `deleteRecursively removes a nested tree and ignores a missing path`() {
        TestFiles.writeText(Path(dir, "x/y/z.txt"), "z")
        val tree = Path(dir, "x")

        TestFiles.deleteRecursively(tree)
        TestFiles.deleteRecursively(tree)

        assertFalse(TestFiles.exists(tree))
        assertTrue(TestFiles.exists(dir))
    }

    @Test
    fun `findRepoRoot finds the build root from the test working directory`() {
        val root = assertNotNull(TestFiles.findRepoRoot())

        assertTrue(TestFiles.exists(Path(root, "settings.gradle.kts")))
        assertTrue(TestFiles.exists(Path(root, "test-fixtures/build.gradle.kts")))
    }

    @Test
    fun `findRepoRoot walks up from a nested start directory`() {
        TestFiles.writeText(Path(dir, "settings.gradle.kts"), "")
        val nested = Path(dir, "a/b")
        TestFiles.writeText(Path(nested, "keep.txt"), "")

        assertEquals(dir, TestFiles.findRepoRoot(nested))
    }

    @Test
    fun `findRepoRoot returns null when no settings file is above the start`() {
        // Not under the temp dir: CI hosts can put it inside the checkout, which has a settings file above it.
        assertNull(TestFiles.findRepoRoot(Path("/karandash-no-such-dir/no/settings")))
    }
}
