package io.karandash.cli.config

import io.karandash.api.errors.ConfigException
import io.karandash.cli.TestFileHelpers
import io.karandash.config.pipeline.ExternalValueResolver
import io.karandash.platform.resolveUserPath
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ExternalValueResolver tests covering simulated CI/CD, Docker, Linux/Windows, and
 * file-content edge cases - all in-process without Docker or OS switching.
 *
 * Environment simulation uses paths typical of each environment for string-based
 * assertions and [TestFileHelpers] for real I/O against the system temp directory.
 */
class ExternalValueResolverEnvironmentTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = TestFileHelpers.createTempDir("evr-env")
    }

    @AfterTest
    fun tearDown() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    private fun write(
        name: String,
        content: String
    ): Path {
        val file = Path(tempDir, name)
        SystemFileSystem.sink(file).buffered().use { it.writeString(content) }
        return file
    }

    // -------------------------------------------------------------------------
    // resolveUserPath: environment-specific base path simulations
    // -------------------------------------------------------------------------

    @Test
    fun `resolveUserPath with CI GitHub Actions base preserves original path`() {
        // Simulate: /home/runner/work/repo/repo is the CI checkout dir
        val ciBase = Path("/home/runner/work/repo/repo")
        val result = resolveUserPath(".karandash/patterns.txt", ciBase)
        assertTrue(result.toString().startsWith("/home/runner/work/repo/repo"))
    }

    @Test
    fun `resolveUserPath with Docker container base preserves original path`() {
        val dockerBase = Path("/app")
        val result = resolveUserPath("config/patterns.txt", dockerBase)
        assertTrue(result.toString().startsWith("/app"))
        assertTrue(result.toString().endsWith("patterns.txt"))
    }

    @Test
    fun `resolveUserPath with GitLab CI base preserves original path`() {
        val gitlabBase = Path("/builds/my-group/my-project")
        val result = resolveUserPath("ci/patterns.txt", gitlabBase)
        assertTrue(result.toString().startsWith("/builds/my-group/my-project"))
    }

    @Test
    fun `resolveUserPath with macOS TMPDIR symlink base does not call realpath`() {
        // /tmp on macOS is a symlink to /private/tmp. resolveUserPath must NOT
        // canonicalize, so the base stays as /tmp/… (not /private/tmp/…).
        val symlinkBase = Path("/", "tmp", "ci-workspace-99") // the symlinked /tmp itself is the subject; nothing is created
        val result = resolveUserPath("patterns.txt", symlinkBase)
        assertTrue(
            result.toString().startsWith("/tmp/ci-workspace-99"),
            "resolveUserPath must not resolve symlinks; expected /tmp prefix, got: $result"
        )
    }

    // -------------------------------------------------------------------------
    // File content edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `resolves file containing only a regex pattern`() {
        val file = write("regex.txt", "^(feat|fix|docs|chore|ci|build|refactor|test|perf|style|revert): .{1,72}")
        val config = buildJsonObject {
            put("pattern", buildJsonObject { put("file", file.toString()) })
        }
        val result = ExternalValueResolver.resolve(config, tempDir)
        val value = ((result as JsonObject)["pattern"] as JsonPrimitive).content
        assertTrue(value.startsWith("^(feat|fix"))
    }

    @Test
    fun `resolves file with only whitespace - trimEnd removes all whitespace-only content`() {
        // All characters are whitespace, so trimEnd() reduces the entire string to ""
        val file = write("spaces.txt", "   \t   \n  \n")
        val config = buildJsonObject {
            put("value", buildJsonObject { put("file", file.toString()) })
        }
        val result = ExternalValueResolver.resolve(config, tempDir)
        assertEquals("", ((result as JsonObject)["value"] as JsonPrimitive).content)
    }

    @Test
    fun `resolves file with multiple trailing newlines - trimEnd removes all`() {
        val file = write("trailing.txt", "^feat:\n\n\n")
        val config = buildJsonObject {
            put("pattern", buildJsonObject { put("file", file.toString()) })
        }
        val result = ExternalValueResolver.resolve(config, tempDir)
        assertEquals("^feat:", ((result as JsonObject)["pattern"] as JsonPrimitive).content)
    }

    @Test
    fun `resolves file with Windows CRLF line endings - trimEnd removes trailing CRLF`() {
        val file = write("crlf.txt", "^(feat|fix):\r\n")
        val config = buildJsonObject {
            put("pattern", buildJsonObject { put("file", file.toString()) })
        }
        val result = ExternalValueResolver.resolve(config, tempDir)
        assertEquals("^(feat|fix):", ((result as JsonObject)["pattern"] as JsonPrimitive).content)
    }

    @Test
    fun `resolves file with Unicode CJK content`() {
        val file = write("cjk.txt", "^(功能|修复|文档):")
        val config = buildJsonObject {
            put("pattern", buildJsonObject { put("file", file.toString()) })
        }
        val result = ExternalValueResolver.resolve(config, tempDir)
        assertEquals("^(功能|修复|文档):", ((result as JsonObject)["pattern"] as JsonPrimitive).content)
    }

    @Test
    fun `resolves file with emoji in content`() {
        val file = write("emoji.txt", "^(feat 🚀|fix 🐛|docs 📝):")
        val config = buildJsonObject {
            put("pattern", buildJsonObject { put("file", file.toString()) })
        }
        val result = ExternalValueResolver.resolve(config, tempDir)
        assertEquals("^(feat 🚀|fix 🐛|docs 📝):", ((result as JsonObject)["pattern"] as JsonPrimitive).content)
    }

    @Test
    fun `resolves file with a very long single-line pattern`() {
        val longPattern = "^(" + (1..50).joinToString("|") { "type$it" } + "):"
        val file = write("long.txt", longPattern)
        val config = buildJsonObject {
            put("pattern", buildJsonObject { put("file", file.toString()) })
        }
        val result = ExternalValueResolver.resolve(config, tempDir)
        assertEquals(longPattern, ((result as JsonObject)["pattern"] as JsonPrimitive).content)
    }

    @Test
    fun `resolves empty file to empty string`() {
        val file = write("empty.txt", "")
        val config = buildJsonObject {
            put("value", buildJsonObject { put("file", file.toString()) })
        }
        val result = ExternalValueResolver.resolve(config, tempDir)
        assertEquals("", ((result as JsonObject)["value"] as JsonPrimitive).content)
    }

    // -------------------------------------------------------------------------
    // Path forms: relative with ./ prefix, subdirectory, deeply nested base
    // -------------------------------------------------------------------------

    @Test
    fun `resolves relative path bare filename against base directory`() {
        write("bare.txt", "bare-value")
        val config = buildJsonObject {
            put("v", buildJsonObject { put("file", "bare.txt") })
        }
        val result = ExternalValueResolver.resolve(config, tempDir)
        assertEquals("bare-value", ((result as JsonObject)["v"] as JsonPrimitive).content)
    }

    @Test
    fun `resolves file in subdirectory via relative path`() {
        val sub = Path(tempDir, "subdir")
        SystemFileSystem.createDirectories(sub)
        SystemFileSystem.sink(Path(sub, "pattern.txt")).buffered().use { it.writeString("sub-value") }

        val config = buildJsonObject {
            put("v", buildJsonObject { put("file", "subdir/pattern.txt") })
        }
        val result = ExternalValueResolver.resolve(config, tempDir)
        assertEquals("sub-value", ((result as JsonObject)["v"] as JsonPrimitive).content)
    }

    @Test
    fun `resolves multiple file references in same config`() {
        write("pattern.txt", "^feat:")
        write("suffix.txt", "JIRA-\\d+")

        val config = buildJsonObject {
            put("pattern", buildJsonObject { put("file", "pattern.txt") })
            put("suffix", buildJsonObject { put("file", "suffix.txt") })
        }
        val result = ExternalValueResolver.resolve(config, tempDir) as JsonObject
        assertEquals("^feat:", (result["pattern"] as JsonPrimitive).content)
        assertEquals("JIRA-\\d+", (result["suffix"] as JsonPrimitive).content)
    }

    @Test
    fun `resolves file reference nested deep in config tree`() {
        write("branch.txt", "^(main|develop|release/.+)")
        val config = buildJsonObject {
            put(
                "rules",
                buildJsonObject {
                    put(
                        "branch-naming",
                        buildJsonObject {
                            put(
                                "platforms",
                                buildJsonObject {
                                    put(
                                        "gitlab",
                                        buildJsonObject {
                                            put("pattern", buildJsonObject { put("file", "branch.txt") })
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }
        val result = ExternalValueResolver.resolve(config, tempDir)
        val pattern = (result as JsonObject)
            .let { it["rules"] as JsonObject }
            .let { it["branch-naming"] as JsonObject }
            .let { it["platforms"] as JsonObject }
            .let { it["gitlab"] as JsonObject }
            .let { it["pattern"] as JsonPrimitive }
        assertEquals("^(main|develop|release/.+)", pattern.content)
    }

    @Test
    fun `resolves file references inside a JSON array`() {
        write("rule-a.txt", "^feat:")
        write("rule-b.txt", "^fix:")
        val config = buildJsonObject {
            put(
                "patterns",
                buildJsonArray {
                    add(buildJsonObject { put("file", "rule-a.txt") })
                    add(buildJsonObject { put("file", "rule-b.txt") })
                }
            )
        }
        val result = ExternalValueResolver.resolve(config, tempDir) as JsonObject
        val patterns = result["patterns"] as JsonArray
        assertEquals("^feat:", (patterns[0] as JsonPrimitive).content)
        assertEquals("^fix:", (patterns[1] as JsonPrimitive).content)
    }

    // -------------------------------------------------------------------------
    // Security denylist - all denylist keys must be blocked
    // -------------------------------------------------------------------------

    @Test
    fun `skips resolution under all security denylist keys`() {
        val file = write("secret.txt", "should-not-be-read")
        val denylisted = listOf("auth", "token", "password", "secret", "credentials", "private-key", "privateKey")

        for (key in denylisted) {
            val config = buildJsonObject {
                put(key, buildJsonObject { put("file", file.toString()) })
            }
            val result = ExternalValueResolver.resolve(config, tempDir) as JsonObject
            assertTrue(
                (result[key] as? JsonObject)?.containsKey("file") == true,
                "Key '$key' must remain unresolved (security denylist)"
            )
        }
    }

    @Test
    fun `resolution proceeds for key adjacent to denylist key`() {
        write("ok.txt", "permitted-value")
        val config = buildJsonObject {
            put("token", buildJsonObject { put("file", "secret.txt") })
            put("pattern", buildJsonObject { put("file", "ok.txt") })
        }
        val result = ExternalValueResolver.resolve(config, tempDir) as JsonObject
        // token must remain unresolved
        assertTrue((result["token"] as JsonObject).containsKey("file"))
        // pattern must be resolved
        assertEquals("permitted-value", (result["pattern"] as JsonPrimitive).content)
    }

    // -------------------------------------------------------------------------
    // Error scenarios
    // -------------------------------------------------------------------------

    @Test
    fun `throws E0023 for missing file with absolute path`() {
        val config = buildJsonObject {
            put("v", buildJsonObject { put("file", "/nonexistent/missing.txt") })
        }
        val ex = assertFailsWith<ConfigException> {
            ExternalValueResolver.resolve(config, tempDir)
        }
        assertTrue(ex.message!!.contains("E0023"))
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test
    fun `throws E0023 for missing relative file`() {
        val config = buildJsonObject {
            put("v", buildJsonObject { put("file", "no-such-file.txt") })
        }
        val ex = assertFailsWith<ConfigException> {
            ExternalValueResolver.resolve(config, tempDir)
        }
        assertTrue(ex.message!!.contains("E0023"))
    }

    @Test
    fun `throws E0023 for blank file path`() {
        val config = buildJsonObject {
            put("v", buildJsonObject { put("file", "   ") })
        }
        val ex = assertFailsWith<ConfigException> {
            ExternalValueResolver.resolve(config, tempDir)
        }
        assertTrue(ex.message!!.contains("E0023"))
        assertTrue(ex.message!!.contains("empty"))
    }

    @Test
    fun `non-file-reference objects are passed through unchanged`() {
        val config = buildJsonObject {
            put(
                "multi",
                buildJsonObject {
                    put("file", "path.txt")
                    put("encoding", "utf-8") // two keys → not a file reference
                }
            )
        }
        val result = ExternalValueResolver.resolve(config, tempDir) as JsonObject
        val multi = result["multi"] as JsonObject
        assertTrue(multi.containsKey("file"))
        assertTrue(multi.containsKey("encoding"))
    }

    @Test
    fun `primitive and boolean values are passed through unchanged`() {
        val config = buildJsonObject {
            put("enabled", true)
            put("count", 42)
            put("name", "myProject")
        }
        val result = ExternalValueResolver.resolve(config, tempDir) as JsonObject
        assertEquals("true", (result["enabled"] as JsonPrimitive).content)
        assertEquals("42", (result["count"] as JsonPrimitive).content)
        assertEquals("myProject", (result["name"] as JsonPrimitive).content)
    }
}
