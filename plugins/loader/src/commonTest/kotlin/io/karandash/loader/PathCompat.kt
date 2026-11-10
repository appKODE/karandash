package io.karandash.loader

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

// Test-only compatibility wrappers while plugin-loader tests migrate to the shared platform helpers.
fun Path.exists(): Boolean = SystemFileSystem.exists(this)

fun Path.isDirectory(): Boolean = SystemFileSystem.metadataOrNull(this)?.isDirectory == true

fun Path.listFiles(): List<Path>? = if (isDirectory()) SystemFileSystem.list(this).toList() else null

fun Path.readBytes(): ByteArray =
    SystemFileSystem.source(this).buffered().use { source ->
        source.readByteArray()
    }

fun Path.writeBytes(data: ByteArray) {
    SystemFileSystem.sink(this).buffered().use { sink ->
        sink.write(data)
        sink.flush()
    }
}

fun Path.writeText(text: String) = writeBytes(text.encodeToByteArray())

fun Path.copyTo(
    destination: Path,
    overwrite: Boolean = false
) {
    if (!overwrite && destination.exists()) {
        error("Destination file already exists: $destination")
    }
    destination.writeBytes(readBytes())
}

val Path.extension: String
    get() = name.substringAfterLast('.', "")

fun Path.fileSize(): Long {
    val metadata = SystemFileSystem.metadataOrNull(this)
        ?: error("File does not exist: $this")
    return metadata.size
}

fun deleteIfExists(path: Path): Boolean {
    if (!path.exists()) return false
    SystemFileSystem.delete(path)
    return true
}

fun createDirectories(path: Path): Boolean {
    if (path.exists()) return false
    SystemFileSystem.createDirectories(path)
    return true
}

fun moveFile(
    from: Path,
    to: Path,
    overwrite: Boolean = false
) {
    if (!overwrite && to.exists()) {
        error("Destination file already exists: $to")
    }
    SystemFileSystem.atomicMove(from, to)
}
