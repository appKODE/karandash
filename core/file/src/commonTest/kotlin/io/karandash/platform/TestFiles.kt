package io.karandash.platform

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.time.Clock

/** Temp-dir helpers for core:file tests; local because test-fixtures has no linuxArm64 target. */
internal object TestFiles {
    /** A fresh, resolved directory under the system temp dir; delete it with [deleteRecursively]. */
    fun createTestTempDirectory(prefix: String): Path {
        val suffix = "${Clock.System.now().toEpochMilliseconds()}-${Random.nextLong().toULong()}"
        val dir = Path(tempDirectory(), "$prefix-$suffix")
        createDirectories(dir)
        // Resolve once (macOS /var -> /private/var) so every derived path shares the canonical base.
        return SystemFileSystem.resolve(dir)
    }

    /** Deletes [path] and everything under it; a missing path is a no-op. */
    fun deleteRecursively(path: Path) {
        if (!SystemFileSystem.exists(path)) return
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach { deleteRecursively(it) }
        }
        SystemFileSystem.delete(path)
    }
}
