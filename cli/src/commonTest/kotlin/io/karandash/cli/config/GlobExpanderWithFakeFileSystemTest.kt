package io.karandash.cli.config

import io.karandash.api.errors.ConfigException
import io.karandash.cli.TestFileHelpers
import io.karandash.utils.GlobExpander
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Additional GlobExpander tests covering multi-level patterns and security constraints.
 * Complements [GlobExpanderTest] with deeper directory traversal and edge-case coverage.
 */
class GlobExpanderWithFakeFileSystemTest {
    @Test
    fun `glob finds kt files in nested src directory`() {
        val testDir = TestFileHelpers.createTempDir("glob-nested-kt-test")
        try {
            SystemFileSystem.createDirectories(Path(testDir, "src"))
            TestFileHelpers.writeFile(Path(testDir, "src", "Foo.kt"), "class Foo")
            TestFileHelpers.writeFile(Path(testDir, "src", "Bar.kt"), "class Bar")
            TestFileHelpers.writeFile(Path(testDir, "src", "readme.txt"), "text")

            val expander = GlobExpander(baseDir = testDir)
            val matches = expander.expand("**/*.kt")

            assertEquals(2, matches.size)
            assertTrue(matches.any { it.endsWith("Foo.kt") })
            assertTrue(matches.any { it.endsWith("Bar.kt") })
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `path traversal in pattern is blocked`() {
        val testDir = TestFileHelpers.createTempDir("glob-security-test")
        try {
            val expander = GlobExpander(baseDir = testDir)
            val ex = assertFailsWith<ConfigException> {
                expander.expand("../../etc/*.yaml")
            }
            assertTrue(ex.message!!.contains("E0020"))
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `glob rejects remote URL patterns`() {
        val testDir = TestFileHelpers.createTempDir("glob-url-test")
        try {
            val expander = GlobExpander(baseDir = testDir)
            val ex = assertFailsWith<ConfigException> {
                expander.expand("https://example.com/*.yaml")
            }
            assertTrue(ex.message!!.contains("remote URL"))
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `glob returns empty for pattern with no matches`() {
        val testDir = TestFileHelpers.createTempDir("glob-empty-test")
        try {
            SystemFileSystem.createDirectories(Path(testDir, "src"))
            TestFileHelpers.writeFile(Path(testDir, "src", "readme.txt"), "text")

            val expander = GlobExpander(baseDir = testDir)
            val matches = expander.expand("**/*.kt")

            assertTrue(matches.isEmpty())
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `glob results are sorted alphabetically`() {
        val testDir = TestFileHelpers.createTempDir("glob-sort-test2")
        try {
            TestFileHelpers.writeFile(Path(testDir, "zz.yaml"), "")
            TestFileHelpers.writeFile(Path(testDir, "aa.yaml"), "")
            TestFileHelpers.writeFile(Path(testDir, "mm.yaml"), "")

            val expander = GlobExpander(baseDir = testDir)
            val matches = expander.expand("*.yaml")

            assertEquals(listOf("aa.yaml", "mm.yaml", "zz.yaml"), matches)
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }
}
