package io.karandash.platform

import kotlinx.io.IOException
import kotlinx.io.files.Path

/**
 * Reads the stdout/stderr capture file of a subprocess. A file that cannot be read yields an empty string and a
 * warning through [warn] (stderr by default), so a lost capture is visible instead of looking like empty output.
 */
internal fun readCaptureFile(
    path: String,
    warn: (String) -> Unit = PlatformEnvironment::stderrPrintln,
): String =
    try {
        Path(path).readText()
    } catch (e: IOException) {
        warn("Warning: cannot read subprocess capture file $path: ${e.message}")
        ""
    }
