package io.karandash.platform

import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * Get the current working directory.
 */
fun currentWorkingDirectory(): Path = Path(getCurrentWorkingDirectoryString())

/**
 * Platform-specific implementation to get current working directory as string.
 */
expect fun getCurrentWorkingDirectoryString(): String

/**
 * Get the user home directory.
 */
fun userHomeDirectory(): Path = Path(getUserHomeDirectoryString())

/**
 * Platform-specific implementation to get user home directory as string.
 */
expect fun getUserHomeDirectoryString(): String

/**
 * Per-OS temp directory used when the system one is blank: `/tmp` on POSIX, `%TEMP%`/`%TMP%`/`C:\Windows\Temp`
 * on Windows (MinGW has no `/tmp`).
 */
expect fun fallbackTempDirectoryString(): String

/**
 * Get the system temporary directory. A blank `TMPDIR` (seen on CI runners) makes kotlinx-io return an
 * empty, cwd-relative path, so that falls back to [fallbackTempDirectoryString].
 */
fun tempDirectory(): Path = SystemTemporaryDirectory.takeIf { it.toString().isNotBlank() } ?: Path(fallbackTempDirectoryString())
