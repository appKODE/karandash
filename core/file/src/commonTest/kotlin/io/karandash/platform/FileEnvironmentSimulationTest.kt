package io.karandash.platform

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Simulates path-resolution behaviour across environments without Docker or OS switching.
 *
 * Each group of tests constructs paths that are representative of a target environment
 * (CI runner, Docker container, Linux, Windows, macOS TMPDIR-symlink) and verifies that
 * [isAbsolutePath], [resolveUserPath], [canRead], and [createTempFileInDir] behave
 * correctly under those conditions.
 */
class FileEnvironmentSimulationTest {
    // -------------------------------------------------------------------------
    // isAbsolutePath - exhaustive path-format coverage
    // -------------------------------------------------------------------------

    // Unix / Linux / macOS
    @Test fun `isAbsolutePath root slash`() = assertTrue(isAbsolutePath("/"))

    @Test fun `isAbsolutePath Unix home path`() = assertTrue(isAbsolutePath("/home/runner/work/project"))

    @Test fun `isAbsolutePath Linux tmp`() = assertTrue(isAbsolutePath("/tmp/workspace"))

    @Test fun `isAbsolutePath Docker app dir`() = assertTrue(isAbsolutePath("/app/config.yaml"))

    @Test fun `isAbsolutePath Docker srv dir`() = assertTrue(isAbsolutePath("/srv/data/file.txt"))

    @Test fun `isAbsolutePath CI GitHub Actions path`() = assertTrue(isAbsolutePath("/home/runner/work/repo/repo/.karandash/config.yaml"))

    @Test fun `isAbsolutePath CI GitLab runner path`() = assertTrue(isAbsolutePath("/builds/group/project/config.yaml"))

    @Test fun `isAbsolutePath CI Jenkins workspace`() = assertTrue(isAbsolutePath("/var/jenkins_home/workspace/job/config.yaml"))

    @Test fun `isAbsolutePath CI CircleCI path`() = assertTrue(isAbsolutePath("/home/circleci/project/.karandash/config.yaml"))

    @Test fun `isAbsolutePath macOS TMPDIR`() = assertTrue(isAbsolutePath("/private/tmp/test-dir/file.txt"))

    @Test fun `isAbsolutePath NFS mount`() = assertTrue(isAbsolutePath("/mnt/nas/configs/base.yaml"))

    @Test fun `isAbsolutePath deep Unix path`() = assertTrue(isAbsolutePath("/a/b/c/d/e/f/g/h/file.txt"))

    @Test fun `isAbsolutePath path with spaces Unix`() = assertTrue(isAbsolutePath("/home/user/my project/config.yaml"))

    @Test fun `isAbsolutePath path with dots in name`() = assertTrue(isAbsolutePath("/tmp/config.backup.yaml"))

    @Test fun `isAbsolutePath path with unicode Unix`() = assertTrue(isAbsolutePath("/home/用户/config.yaml"))

    // Windows
    @Test fun `isAbsolutePath Windows C backslash`() = assertTrue(isAbsolutePath("C:\\Users\\user\\config.yaml"))

    @Test fun `isAbsolutePath Windows C forward slash`() = assertTrue(isAbsolutePath("C:/Users/user/config.yaml"))

    @Test fun `isAbsolutePath Windows D drive`() = assertTrue(isAbsolutePath("D:\\Projects\\karandash\\config.yaml"))

    @Test fun `isAbsolutePath Windows lowercase c`() = assertTrue(isAbsolutePath("c:\\Users\\file.txt"))

    @Test fun `isAbsolutePath Windows z drive`() = assertTrue(isAbsolutePath("z:/data/config.yaml"))

    @Test fun `isAbsolutePath Windows A drive`() = assertTrue(isAbsolutePath("A:\\floppy\\config.txt"))

    // NOT absolute
    @Test fun `isAbsolutePath relative bare filename`() = assertFalse(isAbsolutePath("config.yaml"))

    @Test fun `isAbsolutePath relative with subdir`() = assertFalse(isAbsolutePath("configs/base.yaml"))

    @Test fun `isAbsolutePath dot-slash prefix`() = assertFalse(isAbsolutePath("./config.yaml"))

    @Test fun `isAbsolutePath dot-dot-slash prefix`() = assertFalse(isAbsolutePath("../config.yaml"))

    @Test fun `isAbsolutePath tilde home shorthand`() = assertFalse(isAbsolutePath("~/config.yaml"))

    @Test fun `isAbsolutePath empty string`() = assertFalse(isAbsolutePath(""))

    @Test fun `isAbsolutePath Windows drive without slash`() = assertFalse(isAbsolutePath("C:config.yaml"))

    @Test fun `isAbsolutePath Windows relative with backslash`() = assertFalse(isAbsolutePath("configs\\base.yaml"))

    // -------------------------------------------------------------------------
    // resolveUserPath - environment-specific path construction
    // -------------------------------------------------------------------------

    @Test
    fun `resolveUserPath absolute CI GitHub Actions path is returned unchanged`() {
        val ciBase = Path("/home/runner/work/repo/repo")
        val absoluteFile = "/home/runner/work/repo/repo/.karandash/patterns.txt"
        val result = resolveUserPath(absoluteFile, ciBase)
        assertEquals(absoluteFile, result.toString())
    }

    @Test
    fun `resolveUserPath relative path joined to CI GitHub Actions base`() {
        val ciBase = Path("/home/runner/work/repo/repo")
        val result = resolveUserPath(".karandash/patterns.txt", ciBase)
        // Must start with the CI base path
        assertTrue(result.toString().startsWith("/home/runner/work/repo/repo"))
        assertTrue(result.toString().endsWith("patterns.txt"))
    }

    @Test
    fun `resolveUserPath relative path joined to Docker app base`() {
        val dockerBase = Path("/app")
        val result = resolveUserPath("config/rules.txt", dockerBase)
        assertTrue(result.toString().startsWith("/app"))
        assertTrue(result.toString().endsWith("rules.txt"))
    }

    @Test
    fun `resolveUserPath absolute Docker path is returned unchanged`() {
        val dockerBase = Path("/app")
        val absolute = "/app/config/rules.txt"
        assertEquals(absolute, resolveUserPath(absolute, dockerBase).toString())
    }

    @Test
    fun `resolveUserPath does not call realpath so symlinked TMPDIR base is preserved`() {
        // On macOS, SystemTemporaryDirectory resolves to /private/tmp but the path
        // /tmp still exists as a symlink. resolveUserPath must NOT call realpath(),
        // so a base constructed from /tmp stays as /tmp (not /private/tmp).
        val symlinkBase = Path("/", "tmp", "ci-workspace-12345") // the symlinked /tmp itself is the subject; nothing is created
        val result = resolveUserPath("pattern.txt", symlinkBase)
        // The result must be rooted at the original base, not a canonical form
        assertTrue(result.toString().startsWith("/tmp/ci-workspace-12345"))
    }

    @Test
    fun `resolveUserPath handles path with spaces`() {
        val base = Path("/home/runner/my workspace")
        val result = resolveUserPath("config file.yaml", base)
        assertTrue(result.toString().contains("my workspace"))
        assertTrue(result.toString().endsWith("config file.yaml"))
    }

    @Test
    fun `resolveUserPath handles nested relative path`() {
        val base = Path("/builds/group/project")
        val result = resolveUserPath("infra/karandash/patterns.txt", base)
        assertTrue(result.toString().startsWith("/builds/group/project"))
        assertTrue(result.toString().contains("infra"))
        assertTrue(result.toString().endsWith("patterns.txt"))
    }

    @Test
    fun `resolveUserPath Windows absolute path returned unchanged regardless of base`() {
        val unixBase = Path("/home/user/project")
        val winPath = "C:\\Users\\user\\patterns.txt"
        // Windows absolute path must be detected and returned as-is
        assertEquals(winPath, resolveUserPath(winPath, unixBase).toString())
    }

    @Test
    fun `resolveUserPath Windows forward-slash absolute returned unchanged`() {
        val unixBase = Path("/ci/workspace")
        val winPath = "D:/projects/karandash/patterns.txt"
        assertEquals(winPath, resolveUserPath(winPath, unixBase).toString())
    }

    // -------------------------------------------------------------------------
    // canRead - simulated readable / inaccessible scenarios
    // -------------------------------------------------------------------------

    @Test
    fun `canRead returns false for path that looks like CI workspace but does not exist`() {
        // Simulates a CI runner where the config file is referenced but the checkout
        // hasn't placed the file there yet
        assertFalse(canRead(Path("/home/runner/work/missing-repo/patterns.txt")))
    }

    @Test
    fun `canRead returns false for path that looks like Docker container path`() {
        assertFalse(canRead(Path("/app/config/nonexistent-patterns.txt")))
    }

    @Test
    fun `canRead returns true for file in system temporary directory`() {
        val ts = Clock.System.now().toEpochMilliseconds()
        val dir = TestFiles.createTestTempDirectory("canread-env")
        val file = Path(dir, "readable.txt")
        SystemFileSystem.createDirectories(dir)
        try {
            SystemFileSystem.sink(file).use {}
            assertTrue(canRead(file))
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `canRead returns false immediately after file is deleted`() {
        val ts = Clock.System.now().toEpochMilliseconds()
        val dir = TestFiles.createTestTempDirectory("canread-del")
        val file = Path(dir, "gone.txt")
        SystemFileSystem.createDirectories(dir)
        try {
            SystemFileSystem.sink(file).use {}
            assertTrue(canRead(file))
            SystemFileSystem.delete(file)
            assertFalse(canRead(file), "canRead must return false after deletion")
        } finally {
            if (SystemFileSystem.exists(dir)) SystemFileSystem.delete(dir)
        }
    }

    // -------------------------------------------------------------------------
    // createTempFileInDir - environment-specific temp directories
    // -------------------------------------------------------------------------

    @Test
    fun `createTempFileInDir works in system tmp dir Linux slash-tmp pattern`() {
        val ts = Clock.System.now().toEpochMilliseconds()
        val dir = TestFiles.createTestTempDirectory("ci-tmp")
        SystemFileSystem.createDirectories(dir)
        try {
            val f = createTempFileInDir(dir, "runner-", ".tmp")
            assertTrue(SystemFileSystem.exists(f))
            assertTrue(f.name.startsWith("runner-"))
            assertTrue(f.name.endsWith(".tmp"))
            SystemFileSystem.delete(f)
        } finally {
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `createTempFileInDir with empty prefix creates file`() {
        val ts = Clock.System.now().toEpochMilliseconds()
        val dir = TestFiles.createTestTempDirectory("tmpfile-noprefix")
        SystemFileSystem.createDirectories(dir)
        try {
            val f = createTempFileInDir(dir, "", ".bin")
            assertTrue(SystemFileSystem.exists(f))
            assertTrue(f.name.endsWith(".bin"))
            SystemFileSystem.delete(f)
        } finally {
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `createTempFileInDir with empty suffix creates file`() {
        val ts = Clock.System.now().toEpochMilliseconds()
        val dir = TestFiles.createTestTempDirectory("tmpfile-nosuffix")
        SystemFileSystem.createDirectories(dir)
        try {
            val f = createTempFileInDir(dir, "artifact-", "")
            assertTrue(SystemFileSystem.exists(f))
            assertTrue(f.name.startsWith("artifact-"))
            SystemFileSystem.delete(f)
        } finally {
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `createTempFileInDir with empty prefix and suffix creates file`() {
        val ts = Clock.System.now().toEpochMilliseconds()
        val dir = TestFiles.createTestTempDirectory("tmpfile-bare")
        SystemFileSystem.createDirectories(dir)
        try {
            val f = createTempFileInDir(dir, "", "")
            assertTrue(SystemFileSystem.exists(f))
            SystemFileSystem.delete(f)
        } finally {
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `createTempFileInDir sequential calls produce unique names`() {
        val ts = Clock.System.now().toEpochMilliseconds()
        val dir = TestFiles.createTestTempDirectory("tmpfile-unique")
        SystemFileSystem.createDirectories(dir)
        try {
            val files = (1..10).map { createTempFileInDir(dir, "job-", ".log") }
            val names = files.map { it.name }.toSet()
            assertEquals(10, names.size, "All 10 temp files must have distinct names")
            files.forEach { SystemFileSystem.delete(it) }
        } finally {
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `createTempFileInDir file is empty on creation`() {
        val ts = Clock.System.now().toEpochMilliseconds()
        val dir = TestFiles.createTestTempDirectory("tmpfile-empty")
        SystemFileSystem.createDirectories(dir)
        try {
            val f = createTempFileInDir(dir, "artifact-", ".bin")
            val metadata = SystemFileSystem.metadataOrNull(f)
            assertEquals(0L, metadata?.size, "Newly created temp file must be empty")
            SystemFileSystem.delete(f)
        } finally {
            SystemFileSystem.delete(dir)
        }
    }
}
