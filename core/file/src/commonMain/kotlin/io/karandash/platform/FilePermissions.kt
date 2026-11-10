package io.karandash.platform

import kotlinx.io.files.Path

/**
 * Make a file executable (chmod +x equivalent).
 *
 * On POSIX systems (JVM, macOS, Linux), sets owner/group/other execute bits.
 * On Windows (MinGW), this is a no-op since Windows uses file associations, not execute bits.
 */
expect fun setExecutable(path: Path)
