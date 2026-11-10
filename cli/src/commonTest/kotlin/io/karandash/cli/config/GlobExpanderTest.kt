package io.karandash.cli.config

import io.karandash.api.errors.ConfigException
import io.karandash.cli.TestFileHelpers
import io.karandash.utils.GlobExpander
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test suite for GlobExpander.
 *
 * Coverage:
 * - Pattern expansion with wildcards
 * - Alphabetical sorting
 * - Security constraints:
 *   - Max 10 files limit
 *   - Path traversal blocking
 *   - Max depth limit
 *   - Remote URL rejection
 */
class GlobExpanderTest {
    @Test
    fun `expand simple glob pattern matches files`() {
        val testDir = TestFileHelpers.createTempDir("glob-test")
        try {
            // Create test files
            TestFileHelpers.writeFile(Path(testDir, "config1.yaml"), "rules: {}")
            TestFileHelpers.writeFile(Path(testDir, "config2.yaml"), "rules: {}")
            TestFileHelpers.writeFile(Path(testDir, "other.txt"), "text")

            val expander = GlobExpander(baseDir = testDir)
            val matches = expander.expand("*.yaml")

            assertEquals(2, matches.size)
            assertTrue(matches.contains("config1.yaml"))
            assertTrue(matches.contains("config2.yaml"))
            assertFalse(matches.any { it.contains("other.txt") })
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `expand returns files in alphabetical order`() {
        val testDir = TestFileHelpers.createTempDir("glob-sort-test")
        try {
            // Create files in non-alphabetical order
            TestFileHelpers.writeFile(Path(testDir, "zulu.yaml"), "")
            TestFileHelpers.writeFile(Path(testDir, "alpha.yaml"), "")
            TestFileHelpers.writeFile(Path(testDir, "bravo.yaml"), "")

            val expander = GlobExpander(baseDir = testDir)
            val matches = expander.expand("*.yaml")

            assertEquals(listOf("alpha.yaml", "bravo.yaml", "zulu.yaml"), matches)
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `expand with subdirectory pattern`() {
        val testDir = TestFileHelpers.createTempDir("glob-subdir-test")
        try {
            val configsDir = Path(testDir, "configs")
            SystemFileSystem.createDirectories(configsDir)

            TestFileHelpers.writeFile(Path(configsDir, "base.yaml"), "rules: {}")
            TestFileHelpers.writeFile(Path(configsDir, "prod.yaml"), "rules: {}")
            TestFileHelpers.writeFile(Path(testDir, "root.yaml"), "rules: {}")

            val expander = GlobExpander(baseDir = testDir)
            val matches = expander.expand("configs/*.yaml")

            assertEquals(2, matches.size)
            assertTrue(matches.contains("configs/base.yaml"))
            assertTrue(matches.contains("configs/prod.yaml"))
            assertFalse(matches.any { it.contains("root.yaml") })
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `expand recursive pattern with double star`() {
        val testDir = TestFileHelpers.createTempDir("glob-recursive-test")
        try {
            val level1 = Path(testDir, "level1")
            val level2 = Path(level1, "level2")
            SystemFileSystem.createDirectories(level2)

            TestFileHelpers.writeFile(Path(testDir, "root.json"), "{}")
            TestFileHelpers.writeFile(Path(level1, "mid.json"), "{}")
            TestFileHelpers.writeFile(Path(level2, "deep.json"), "{}")

            val expander = GlobExpander(baseDir = testDir)
            val matches = expander.expand("**/*.json")

            assertEquals(3, matches.size)
            assertTrue(matches.contains("root.json"))
            assertTrue(matches.contains("level1/mid.json"))
            assertTrue(matches.contains("level1/level2/deep.json"))
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `expand throws E0015 when pattern expands beyond max limit`() {
        val testDir = TestFileHelpers.createTempDir("glob-limit-test")
        try {
            // Create 11 files (exceeds MAX_GLOB_EXPANSION of 10)
            for (i in 1..11) {
                TestFileHelpers.writeFile(Path(testDir, "config$i.yaml"), "rules: {}")
            }

            val expander = GlobExpander(baseDir = testDir)
            val exception = assertFailsWith<ConfigException> {
                expander.expand("*.yaml")
            }

            assertTrue(exception.message!!.contains("E0015"))
            assertTrue(exception.message!!.contains("11 files"))
            assertTrue(exception.message!!.contains("max: 10"))
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `expand throws E0020 on path traversal attempt`() {
        val testDir = TestFileHelpers.createTempDir("glob-traversal-test")
        try {
            val expander = GlobExpander(baseDir = testDir)

            val traversalPatterns = listOf(
                "../../etc/*.yaml",
                "../../../secrets/*.yaml",
                "subdir/../../../../../../etc/passwd"
            )

            for (pattern in traversalPatterns) {
                val exception = assertFailsWith<ConfigException> {
                    expander.expand(pattern)
                }

                assertTrue(
                    exception.message!!.contains("E0020"),
                    "Expected E0020 for pattern: $pattern, got: ${exception.message}"
                )
                assertTrue(
                    exception.message!!.contains("path traversal") ||
                        exception.message!!.contains("security constraints"),
                    "Expected path traversal message for pattern: $pattern"
                )
            }
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `expand throws E0015 when base directory does not exist`() {
        val testDir = TestFileHelpers.createTempDir("glob-nonexistent-test")
        try {
            val expander = GlobExpander(baseDir = testDir)
            val exception = assertFailsWith<ConfigException> {
                expander.expand("nonexistent/*.yaml")
            }

            assertTrue(exception.message!!.contains("E0015"))
            assertTrue(exception.message!!.contains("does not exist"))
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `expand throws E0015 for remote URL patterns`() {
        val testDir = TestFileHelpers.createTempDir("glob-url-test")
        try {
            val expander = GlobExpander(baseDir = testDir)

            val urlPatterns = listOf(
                "https://example.com/*.yaml",
                "http://company.com/configs/*.json",
                "ftp://server.com/data/*.yml",
                "git://github.com/user/repo/*.yaml"
            )

            for (pattern in urlPatterns) {
                val exception = assertFailsWith<ConfigException> {
                    expander.expand(pattern)
                }

                assertTrue(
                    exception.message!!.contains("E0015"),
                    "Expected E0015 for URL pattern: $pattern"
                )
                assertTrue(
                    exception.message!!.contains("remote URL"),
                    "Expected remote URL error for pattern: $pattern"
                )
                assertTrue(
                    exception.message!!.contains("manifest"),
                    "Expected manifest suggestion for pattern: $pattern"
                )
            }
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `expand enforces max directory depth`() {
        val testDir = TestFileHelpers.createTempDir("glob-depth-test")
        try {
            // Create directory tree deeper than MAX_DIRECTORY_DEPTH (5)
            var currentPath = testDir
            for (i in 1..7) {
                currentPath = Path(currentPath, "level$i")
                SystemFileSystem.createDirectories(currentPath)
            }
            TestFileHelpers.writeFile(Path(currentPath, "deep.yaml"), "rules: {}")

            val expander = GlobExpander(baseDir = testDir)
            val matches = expander.expand("**/*.yaml")

            // Should not find the file at depth 7 (beyond limit)
            assertEquals(0, matches.size)
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `expand returns empty list when no files match pattern`() {
        val testDir = TestFileHelpers.createTempDir("glob-nomatch-test")
        try {
            TestFileHelpers.writeFile(Path(testDir, "config.yaml"), "rules: {}")

            val expander = GlobExpander(baseDir = testDir)
            val matches = expander.expand("*.json")

            assertEquals(0, matches.size)
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `expand handles literal path without wildcards`() {
        val testDir = TestFileHelpers.createTempDir("glob-literal-test")
        try {
            val configsDir = Path(testDir, "configs")
            SystemFileSystem.createDirectories(configsDir)
            TestFileHelpers.writeFile(Path(configsDir, "base.yaml"), "rules: {}")

            val expander = GlobExpander(baseDir = testDir)
            val matches = expander.expand("configs/base.yaml")

            assertEquals(1, matches.size)
            assertEquals("configs/base.yaml", matches[0])
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `expand with question mark wildcard`() {
        val testDir = TestFileHelpers.createTempDir("glob-question-test")
        try {
            TestFileHelpers.writeFile(Path(testDir, "config1.yaml"), "")
            TestFileHelpers.writeFile(Path(testDir, "config2.yaml"), "")
            TestFileHelpers.writeFile(Path(testDir, "config10.yaml"), "")

            val expander = GlobExpander(baseDir = testDir)
            val matches = expander.expand("config?.yaml")

            assertEquals(2, matches.size)
            assertTrue(matches.contains("config1.yaml"))
            assertTrue(matches.contains("config2.yaml"))
            assertFalse(matches.contains("config10.yaml"))
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `expand only matches files not directories`() {
        val testDir = TestFileHelpers.createTempDir("glob-files-only-test")
        try {
            TestFileHelpers.writeFile(Path(testDir, "config.yaml"), "rules: {}")
            SystemFileSystem.createDirectories(Path(testDir, "config.yaml.dir"))

            val expander = GlobExpander(baseDir = testDir)
            val matches = expander.expand("*.yaml")

            assertEquals(1, matches.size)
            assertEquals("config.yaml", matches[0])
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `expand handles mixed extensions in pattern`() {
        val testDir = TestFileHelpers.createTempDir("glob-mixed-test")
        try {
            TestFileHelpers.writeFile(Path(testDir, "a.yaml"), "")
            TestFileHelpers.writeFile(Path(testDir, "b.yml"), "")
            TestFileHelpers.writeFile(Path(testDir, "c.json"), "")

            val expander = GlobExpander(baseDir = testDir)
            val yamlMatches = expander.expand("*.yaml")
            val ymlMatches = expander.expand("*.yml")
            val jsonMatches = expander.expand("*.json")

            assertEquals(1, yamlMatches.size)
            assertEquals(1, ymlMatches.size)
            assertEquals(1, jsonMatches.size)
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `expand stops early when exceeding limit during walk`() {
        val testDir = TestFileHelpers.createTempDir("glob-early-stop-test")
        try {
            // Create 15 files so we exceed the limit
            for (i in 1..15) {
                TestFileHelpers.writeFile(Path(testDir, "file$i.yaml"), "")
            }

            val expander = GlobExpander(baseDir = testDir)
            val exception = assertFailsWith<ConfigException> {
                expander.expand("*.yaml")
            }

            // Should fail with E0015 for exceeding limit
            assertTrue(exception.message!!.contains("E0015"))
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }
}
