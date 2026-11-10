package io.karandash.platform

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

/**
 * JVM implementation of [lastModifiedMillis] using [File.lastModified].
 */
actual fun Path.lastModifiedMillis(): Long = File(this.toString()).lastModified()

internal actual fun createFileExclusive(path: Path): Boolean {
    val target = Paths.get(path.toString())
    return try {
        if (Files.getFileStore(target.toAbsolutePath().parent).supportsFileAttributeView("posix")) {
            Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createFile(target)
        }
        true
    } catch (_: FileAlreadyExistsException) {
        false
    }
}

actual val Path.safeParent: Path? get() = parent

actual val Path.safeName: String get() = name

internal actual fun createDirectoriesRaw(path: Path) = SystemFileSystem.createDirectories(path)
