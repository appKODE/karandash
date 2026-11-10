package io.karandash.platform

import kotlinx.io.files.Path
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertTrue

/** `lastModifiedMillis` keeps the sub-second part of the mtime instead of truncating to whole seconds. */
@OptIn(ExperimentalNativeApi::class)
class LastModifiedPrecisionTest {
    @Test
    fun `lastModifiedMillis keeps millisecond precision`() {
        // Windows stat() reports whole seconds only
        if (Platform.osFamily == OsFamily.WINDOWS) return
        val dir = TestFiles.createTestTempDirectory("ci11-mtime")
        try {
            // a whole-second mtime is 1 in 1000 per file, so 20 files that all land on one is a truncation, not luck
            val mtimes = (0 until FILES).map { Path(dir, "f$it.txt").also { file -> file.writeText("x") }.lastModifiedMillis() }
            assertTrue(mtimes.any { it % MILLIS_PER_SECOND != 0L }, "every mtime was a whole second: $mtimes")
        } finally {
            TestFiles.deleteRecursively(dir)
        }
    }

    private companion object {
        const val FILES = 20
        const val MILLIS_PER_SECOND = 1000L
    }
}
