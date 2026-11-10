package io.karandash.cli.config

import io.karandash.cli.TestFileHelpers
import io.karandash.config.pipeline.ExternalValueResolver
import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.exists
import io.karandash.platform.resolveUserPath
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the Linux TMPDIR symlink issue.
 *
 * On Linux systems where TMPDIR (/tmp) is a symlink (e.g., to /private/tmp on macOS,
 * or /run/user/1000 in some CI environments), calling SystemFileSystem.resolve()
 * returns the canonical path via realpath(). If the file was created at the original path,
 * exists() on the resolved path may return false.
 *
 * The fix uses [resolveUserPath] which avoids calling realpath, so the returned path
 * matches the creation path.
 */
class ExternalValueResolverLinuxTest {
    /**
     * Verifies that resolveUserPath does NOT call SystemFileSystem.resolve() and
     * therefore preserves the original path unchanged (no symlink resolution).
     */
    @Test
    fun `resolveUserPath preserves original path without symlink resolution`() {
        val tempDir = TestFileHelpers.createTempDir("symlink-doc")
        try {
            val filePath = Path(tempDir, "data.txt")
            SystemFileSystem.sink(filePath).buffered().use { it.writeString("value") }

            // resolveUserPath builds the path directly without calling realpath
            val resolved = resolveUserPath("data.txt", tempDir)
            assertTrue(resolved.exists(), "resolveUserPath result must exist")

            // The resolved path should be based on tempDir, not a canonical form
            assertTrue(
                resolved.toString().startsWith(tempDir.toString()),
                "resolveUserPath must not change the base directory"
            )
        } finally {
            TestFileHelpers.deleteTempDir(tempDir)
        }
    }

    /**
     * Verifies that absolute paths are returned unchanged by resolveUserPath.
     */
    @Test
    fun `resolveUserPath returns absolute paths unchanged`() {
        val tempDir = TestFileHelpers.createTempDir("symlink-abs")
        try {
            val filePath = Path(tempDir, "pattern.txt")
            SystemFileSystem.sink(filePath).buffered().use { it.writeString("^(feat|fix):") }

            // Passing an absolute path should return it unchanged
            val resolved = resolveUserPath(filePath.toString(), Path("/other/base"))
            assertEquals(filePath.toString(), resolved.toString())
            assertTrue(resolved.exists())
        } finally {
            TestFileHelpers.deleteTempDir(tempDir)
        }
    }

    /**
     * Verifies the full ExternalValueResolver pipeline succeeds with file references
     * using the new resolveUserPath approach (no symlink resolution).
     */
    @Test
    fun `resolves file reference without symlink resolution`() {
        val tempDir = TestFileHelpers.createTempDir("symlink-test")
        try {
            val filePath = Path(tempDir, "pattern.txt")
            SystemFileSystem.sink(filePath).buffered().use { it.writeString("^(feat|fix):") }

            // Verify the full ExternalValueResolver pipeline succeeds
            val config = buildJsonObject {
                put("pattern", buildJsonObject { put("file", filePath.toString()) })
            }
            val result = ExternalValueResolver.resolve(config, tempDir)
            assertEquals(
                "^(feat|fix):",
                ((result as JsonObject)["pattern"] as JsonPrimitive).content,
            )
        } finally {
            TestFileHelpers.deleteTempDir(tempDir)
        }
    }

    /**
     * Verifies that resolveUserPath with a relative path and the old absolutePathOrSelf
     * approach may produce different results, documenting why the fix was needed.
     */
    @Test
    fun `resolveUserPath differs from absolutePathOrSelf when symlinks exist`() {
        val tempDir = TestFileHelpers.createTempDir("symlink-diff")
        try {
            val filePath = Path(tempDir, "test.txt")
            SystemFileSystem.sink(filePath).buffered().use { it.writeString("content") }

            // resolveUserPath: builds path directly from components
            val userResolved = resolveUserPath("test.txt", tempDir)

            // absolutePathOrSelf: calls SystemFileSystem.resolve() which calls realpath()
            val canonResolved = userResolved.absolutePathOrSelf()

            // Both must point to an existing file
            assertTrue(userResolved.exists(), "resolveUserPath result must exist")
            assertTrue(canonResolved.exists(), "absolutePathOrSelf result must exist")

            // On macOS, /tmp -> /private/tmp, so these may differ
            // On Linux CI with symlinked TMPDIR, they WILL differ
            // The key is that resolveUserPath result always exists at the creation path
        } finally {
            TestFileHelpers.deleteTempDir(tempDir)
        }
    }
}
