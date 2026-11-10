package io.karandash.platform

import kotlinx.io.files.Path

actual fun setExecutable(path: Path) {
    // No-op: Windows uses file associations, not POSIX permission bits.
}
