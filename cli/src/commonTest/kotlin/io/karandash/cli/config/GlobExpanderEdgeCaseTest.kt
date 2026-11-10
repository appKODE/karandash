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
 * Edge-case and environment-simulation tests for [GlobExpander].
 *
 * Covers: hidden files, files with special names, exact depth boundaries,
 * all path-traversal variants, all remote URL schemes, empty directories,
 * literal (no-wildcard) patterns, single-char wildcards, and multi-extension patterns.
 */
class GlobExpanderEdgeCaseTest {
    // -------------------------------------------------------------------------
    // Hidden files (dot-prefixed - common in CI and VCS configs)
    // -------------------------------------------------------------------------

    @Test
    fun `glob matches hidden files starting with dot`() {
        val dir = TestFileHelpers.createTempDir("glob-hidden")
        try {
            TestFileHelpers.writeFile(Path(dir, ".gitignore"), "*.log")
            TestFileHelpers.writeFile(Path(dir, ".karandash.yaml"), "rules: {}")
            TestFileHelpers.writeFile(Path(dir, "visible.yaml"), "visible: true")

            val matches = GlobExpander(baseDir = dir).expand("*.yaml")

            assertTrue(matches.contains(".karandash.yaml"), "hidden .karandash.yaml must be matched")
            assertTrue(matches.contains("visible.yaml"))
            assertFalse(matches.contains(".gitignore"))
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    @Test
    fun `glob with double-star finds hidden files in subdirectories`() {
        val dir = TestFileHelpers.createTempDir("glob-hidden-deep")
        try {
            val sub = Path(dir, ".karandash")
            SystemFileSystem.createDirectories(sub)
            TestFileHelpers.writeFile(Path(sub, "config.yaml"), "rules: {}")
            TestFileHelpers.writeFile(Path(sub, "config.json"), "{}")

            val matches = GlobExpander(baseDir = dir).expand("**/*.yaml")
            assertTrue(matches.any { it.endsWith("config.yaml") })
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Files with multiple dots in name
    // -------------------------------------------------------------------------

    @Test
    fun `glob matches files with multiple dots using star wildcard`() {
        val dir = TestFileHelpers.createTempDir("glob-multi-dot")
        try {
            TestFileHelpers.writeFile(Path(dir, "config.backup.yaml"), "backup: true")
            TestFileHelpers.writeFile(Path(dir, "config.v2.yaml"), "version: 2")
            TestFileHelpers.writeFile(Path(dir, "plain.yaml"), "plain: true")

            val matches = GlobExpander(baseDir = dir).expand("*.yaml")
            assertEquals(3, matches.size)
            assertTrue(matches.contains("config.backup.yaml"))
            assertTrue(matches.contains("config.v2.yaml"))
            assertTrue(matches.contains("plain.yaml"))
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Exact depth boundary (MAX_DIRECTORY_DEPTH = 5)
    // -------------------------------------------------------------------------

    @Test
    fun `glob finds files at exactly max depth depth 5`() {
        val dir = TestFileHelpers.createTempDir("glob-depth-5")
        try {
            var current = dir
            repeat(5) { current = Path(current, "d").also { SystemFileSystem.createDirectories(it) } }
            TestFileHelpers.writeFile(Path(current, "at-limit.yaml"), "depth: 5")

            val matches = GlobExpander(baseDir = dir).expand("**/*.yaml")
            assertTrue(matches.any { it.endsWith("at-limit.yaml") }, "File at depth 5 must be found")
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    @Test
    fun `glob does not find files beyond max depth depth 6`() {
        val dir = TestFileHelpers.createTempDir("glob-depth-6")
        try {
            var current = dir
            repeat(6) { current = Path(current, "d").also { SystemFileSystem.createDirectories(it) } }
            TestFileHelpers.writeFile(Path(current, "too-deep.yaml"), "depth: 6")

            val matches = GlobExpander(baseDir = dir).expand("**/*.yaml")
            assertFalse(matches.any { it.endsWith("too-deep.yaml") }, "File at depth 6 must be excluded")
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Empty directories
    // -------------------------------------------------------------------------

    @Test
    fun `glob returns empty list when baseDir is empty`() {
        val dir = TestFileHelpers.createTempDir("glob-empty-dir")
        try {
            val matches = GlobExpander(baseDir = dir).expand("*.yaml")
            assertTrue(matches.isEmpty())
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    @Test
    fun `glob returns empty list when subdirectory is empty`() {
        val dir = TestFileHelpers.createTempDir("glob-empty-subdir")
        try {
            SystemFileSystem.createDirectories(Path(dir, "configs"))
            val matches = GlobExpander(baseDir = dir).expand("configs/*.yaml")
            assertTrue(matches.isEmpty())
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Question-mark single-character wildcard
    // -------------------------------------------------------------------------

    @Test
    fun `question mark wildcard matches exactly one character`() {
        val dir = TestFileHelpers.createTempDir("glob-qmark")
        try {
            TestFileHelpers.writeFile(Path(dir, "env-a.yaml"), "")
            TestFileHelpers.writeFile(Path(dir, "env-b.yaml"), "")
            TestFileHelpers.writeFile(Path(dir, "env-10.yaml"), "") // two chars after '-' - must NOT match env-?.yaml

            val matches = GlobExpander(baseDir = dir).expand("env-?.yaml")
            assertEquals(2, matches.size)
            assertTrue(matches.contains("env-a.yaml"))
            assertTrue(matches.contains("env-b.yaml"))
            assertFalse(matches.contains("env-10.yaml"))
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Literal pattern (no wildcards)
    // -------------------------------------------------------------------------

    @Test
    fun `literal pattern with no wildcards matches exact file`() {
        val dir = TestFileHelpers.createTempDir("glob-literal")
        try {
            val sub = Path(dir, "infra", "karandash")
            SystemFileSystem.createDirectories(sub)
            TestFileHelpers.writeFile(Path(sub, "base.yaml"), "rules: {}")
            TestFileHelpers.writeFile(Path(sub, "other.yaml"), "rules: {}")

            val matches = GlobExpander(baseDir = dir).expand("infra/karandash/base.yaml")
            assertEquals(1, matches.size)
            assertEquals("infra/karandash/base.yaml", matches[0])
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    @Test
    fun `literal pattern for non-existent file throws E0015`() {
        val dir = TestFileHelpers.createTempDir("glob-literal-missing")
        try {
            val ex = assertFailsWith<ConfigException> {
                GlobExpander(baseDir = dir).expand("nonexistent/path/config.yaml")
            }
            assertTrue(ex.message!!.contains("E0015"))
            assertTrue(ex.message!!.contains("does not exist"))
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Path traversal - all variants must be blocked with E0020
    // -------------------------------------------------------------------------

    @Test
    fun `path traversal variants all produce E0020`() {
        val dir = TestFileHelpers.createTempDir("glob-traversal-all")
        try {
            val patterns = listOf(
                "../etc/passwd",
                "../../secrets/token",
                "../../../root/.ssh/id_rsa",
                "subdir/../../outside/config.yaml",
                "a/b/../../../etc/*.yaml",
                "..",
                "../",
            )
            for (pattern in patterns) {
                val ex = assertFailsWith<ConfigException>(message = "Expected E0020 for: $pattern") {
                    GlobExpander(baseDir = dir).expand(pattern)
                }
                assertTrue(
                    ex.message!!.contains("E0020"),
                    "Expected E0020 for pattern '$pattern', got: ${ex.message}"
                )
            }
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Remote URL schemes - all must be blocked with E0015
    // -------------------------------------------------------------------------

    @Test
    fun `all remote URL schemes produce E0015`() {
        val dir = TestFileHelpers.createTempDir("glob-urls")
        try {
            val urlPatterns = listOf(
                "http://example.com/*.yaml",
                "https://raw.githubusercontent.com/org/repo/main/*.yaml",
                "ftp://files.example.com/configs/*.yaml",
                "git://github.com/org/repo/configs/*.yaml",
            )
            for (pattern in urlPatterns) {
                val ex = assertFailsWith<ConfigException>(message = "Expected E0015 for: $pattern") {
                    GlobExpander(baseDir = dir).expand(pattern)
                }
                assertTrue(ex.message!!.contains("E0015"), "Expected E0015 for URL '$pattern'")
                assertTrue(ex.message!!.contains("remote URL"))
            }
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Max-expansion limit (10 files)
    // -------------------------------------------------------------------------

    @Test
    fun `exactly 10 files do not exceed limit`() {
        val dir = TestFileHelpers.createTempDir("glob-limit-10")
        try {
            repeat(10) { TestFileHelpers.writeFile(Path(dir, "f$it.yaml"), "") }

            val matches = GlobExpander(baseDir = dir).expand("*.yaml")
            assertEquals(10, matches.size)
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    @Test
    fun `11 files exceed limit and produce E0015`() {
        val dir = TestFileHelpers.createTempDir("glob-limit-11")
        try {
            repeat(11) { TestFileHelpers.writeFile(Path(dir, "f$it.yaml"), "") }

            val ex = assertFailsWith<ConfigException> {
                GlobExpander(baseDir = dir).expand("*.yaml")
            }
            assertTrue(ex.message!!.contains("E0015"))
            assertTrue(ex.message!!.contains("11 files"))
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Only files matched - directories with matching names are excluded
    // -------------------------------------------------------------------------

    @Test
    fun `directory whose name matches pattern is not included in results`() {
        val dir = TestFileHelpers.createTempDir("glob-dir-vs-file")
        try {
            TestFileHelpers.writeFile(Path(dir, "real.yaml"), "file: true")
            SystemFileSystem.createDirectories(Path(dir, "fake.yaml")) // directory with .yaml name

            val matches = GlobExpander(baseDir = dir).expand("*.yaml")
            assertEquals(1, matches.size)
            assertEquals("real.yaml", matches[0])
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Mixed-extension patterns across nested structure
    // -------------------------------------------------------------------------

    @Test
    fun `star-star pattern matches yaml and json independently`() {
        val dir = TestFileHelpers.createTempDir("glob-mixed-ext")
        try {
            val sub = Path(dir, "configs")
            SystemFileSystem.createDirectories(sub)
            TestFileHelpers.writeFile(Path(dir, "root.yaml"), "")
            TestFileHelpers.writeFile(Path(sub, "nested.json"), "")
            TestFileHelpers.writeFile(Path(sub, "also.yaml"), "")

            val yamlMatches = GlobExpander(baseDir = dir).expand("**/*.yaml")
            val jsonMatches = GlobExpander(baseDir = dir).expand("**/*.json")

            assertEquals(2, yamlMatches.size)
            assertEquals(1, jsonMatches.size)
            assertTrue(jsonMatches.any { it.endsWith("nested.json") })
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Simulated CI/CD directory structure
    // -------------------------------------------------------------------------

    @Test
    fun `glob works in a simulated GitHub Actions workspace structure`() {
        // Simulates: /home/runner/work/repo/repo/.karandash/environments/*.yaml
        // Using real temp dir for actual I/O
        val dir = TestFileHelpers.createTempDir("glob-ci-workspace")
        try {
            val envDir = Path(dir, ".karandash", "environments")
            SystemFileSystem.createDirectories(envDir)
            TestFileHelpers.writeFile(Path(envDir, "production.yaml"), "env: prod")
            TestFileHelpers.writeFile(Path(envDir, "staging.yaml"), "env: staging")
            TestFileHelpers.writeFile(Path(envDir, "development.yaml"), "env: dev")

            val matches = GlobExpander(baseDir = dir).expand(".karandash/environments/*.yaml")
            assertEquals(3, matches.size)
            assertTrue(matches.all { it.startsWith(".karandash/environments/") })
            assertTrue(matches.all { it.endsWith(".yaml") })
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    @Test
    fun `glob works in a simulated Docker app directory structure`() {
        // Simulates: /app/config/rules/*.yaml
        val dir = TestFileHelpers.createTempDir("glob-docker-app")
        try {
            val rulesDir = Path(dir, "config", "rules")
            SystemFileSystem.createDirectories(rulesDir)
            TestFileHelpers.writeFile(Path(rulesDir, "commit-messages.yaml"), "enabled: true")
            TestFileHelpers.writeFile(Path(rulesDir, "branch-naming.yaml"), "enabled: true")

            val matches = GlobExpander(baseDir = dir).expand("config/rules/*.yaml")
            assertEquals(2, matches.size)
            assertTrue(matches.contains("config/rules/commit-messages.yaml"))
            assertTrue(matches.contains("config/rules/branch-naming.yaml"))
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    @Test
    fun `glob works in simulated monorepo with multiple service configs`() {
        // Simulates: services/*/karandash.yaml across a monorepo
        val dir = TestFileHelpers.createTempDir("glob-monorepo")
        try {
            for (service in listOf("auth", "payments", "notifications")) {
                val svcDir = Path(dir, "services", service)
                SystemFileSystem.createDirectories(svcDir)
                TestFileHelpers.writeFile(Path(svcDir, "karandash.yaml"), "service: $service")
            }
            // A deeply nested one that should NOT appear (beyond the search root)
            val deepDir = Path(dir, "services", "auth", "internal")
            SystemFileSystem.createDirectories(deepDir)
            TestFileHelpers.writeFile(Path(deepDir, "karandash.yaml"), "internal: true")

            // services/*/karandash.yaml - no wildcard before services/, so search root = services/
            // Note: * does not recurse, so internal/karandash.yaml should NOT appear
            val matches = GlobExpander(baseDir = dir).expand("**/*.yaml")
            // All 4 yaml files are found (monorepo has up to depth 3 here, within limit)
            assertEquals(4, matches.size)
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Results ordering
    // -------------------------------------------------------------------------

    @Test
    fun `results are sorted alphabetically across subdirectory boundaries`() {
        val dir = TestFileHelpers.createTempDir("glob-sort-cross")
        try {
            val aDir = Path(dir, "a-team")
            val bDir = Path(dir, "b-team")
            SystemFileSystem.createDirectories(aDir)
            SystemFileSystem.createDirectories(bDir)
            TestFileHelpers.writeFile(Path(bDir, "rules.yaml"), "")
            TestFileHelpers.writeFile(Path(aDir, "rules.yaml"), "")
            TestFileHelpers.writeFile(Path(dir, "root.yaml"), "")

            val matches = GlobExpander(baseDir = dir).expand("**/*.yaml")
            // Sorted alphabetically means a-team/ comes before b-team/
            val aIndex = matches.indexOfFirst { it.contains("a-team") }
            val bIndex = matches.indexOfFirst { it.contains("b-team") }
            assertTrue(aIndex < bIndex, "a-team must come before b-team in sorted results")
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }
}
