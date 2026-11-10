package io.karandash.loader

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

fun deleteTestDirectory(path: Path) {
    if (!SystemFileSystem.exists(path)) return

    if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
        SystemFileSystem.list(path).forEach { deleteTestDirectory(it) }
    }
    SystemFileSystem.delete(path)
}
