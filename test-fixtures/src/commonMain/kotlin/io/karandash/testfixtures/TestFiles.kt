package io.karandash.testfixtures

import io.karandash.platform.createDirectories
import io.karandash.platform.safeParent
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.time.Clock

/** kotlinx-io file helpers for tests on every target: temp dirs, text files, recursive delete, the build root. */
object TestFiles {
    private const val SETTINGS_FILE = "settings.gradle.kts"

    /** A fresh, resolved directory under the system temp dir; delete it with [deleteRecursively]. */
    fun createTestTempDirectory(prefix: String): Path {
        val suffix = "${Clock.System.now().toEpochMilliseconds()}-${Random.nextLong().toULong()}"
        val dir = Path(SystemTemporaryDirectory, "$prefix-$suffix")
        createDirectories(dir)
        // Resolve once (macOS /var -> /private/var) so every derived path shares the canonical base.
        return SystemFileSystem.resolve(dir)
    }

    /** Deletes [path] and everything under it; a missing path is a no-op. */
    fun deleteRecursively(path: Path) {
        if (!exists(path)) return
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach { deleteRecursively(it) }
        }
        SystemFileSystem.delete(path)
    }

    fun exists(path: Path): Boolean = SystemFileSystem.exists(path)

    fun readText(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readByteArray().decodeToString() }

    /** Writes [text] as UTF-8, creating parent directories. */
    fun writeText(
        path: Path,
        text: String,
    ) {
        path.safeParent?.let { createDirectories(it) }
        SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
    }

    /**
     * The nearest directory at or above [start] (default: the test working directory) holding `settings.gradle.kts`,
     * or null. Lets tests address repo files whether Gradle runs them from the module or the root directory.
     */
    fun findRepoRoot(start: Path = SystemFileSystem.resolve(Path("."))): Path? =
        generateSequence(start) { it.safeParent }.firstOrNull { exists(Path(it, SETTINGS_FILE)) }
}
