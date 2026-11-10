package io.karandash.platform

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** when mkstemp fails the capture file is an error, not a predictable fallback name. */
class TempCapturePathTest {
    @Test
    fun `capture file creation fails instead of guessing a name`() {
        val error = assertFailsWith<IllegalStateException> {
            PlatformProcess.createTempCapturePath("karandash-stdout", "/karandash-no-such-dir-sec15")
        }
        assertTrue(error.message.orEmpty().contains("/karandash-no-such-dir-sec15"), error.message)
    }

    @Test
    fun `capture file is created by mkstemp in the given directory`() {
        val path = PlatformProcess.createTempCapturePath("karandash-stdout", tempDirectory().toString())
        assertTrue(
            kotlinx.io.files
                .Path(path)
                .exists(),
            path
        )
        deleteIfExists(kotlinx.io.files.Path(path))
    }
}
