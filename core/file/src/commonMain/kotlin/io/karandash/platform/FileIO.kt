package io.karandash.platform

import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.random.Random

/**
 * Check if this path exists.
 */
fun Path.exists(): Boolean = SystemFileSystem.exists(this)

/**
 * Check if this path is a regular file.
 */
fun Path.isFile(): Boolean = SystemFileSystem.metadataOrNull(this)?.isRegularFile == true

/**
 * Check if this path is a directory.
 */
fun Path.isDirectory(): Boolean = SystemFileSystem.metadataOrNull(this)?.isDirectory == true

/**
 * List all entries (files and subdirectories) of a directory; the single directory-listing helper.
 *
 * @return list of child paths, or null if this path is not a directory
 */
fun Path.listFiles(): List<Path>? {
    if (!isDirectory()) return null
    return SystemFileSystem.list(this).toList()
}

/**
 * Read the entire file as text using buffered I/O.
 */
fun Path.readText(): String = readBytes().decodeToString()

/**
 * Write text to a file using buffered I/O.
 */
fun Path.writeText(text: String) = writeBytes(text.encodeToByteArray())

/**
 * Get the absolute canonical path by resolving ALL symbolic links (like POSIX realpath).
 *
 * **WARNING**: Resolving symlinks can produce a path DIFFERENT from the one used to write
 * a file, causing subsequent [exists] calls to return false on Linux systems where
 * TMPDIR or other directories use symlinks. Only use this for security path-traversal
 * validation. For user-provided file paths, use [resolveUserPath].
 *
 * @throws kotlinx.io.IOException if the path cannot be resolved (e.g. does not exist).
 */
fun Path.absolutePath(): Path = SystemFileSystem.resolve(this)

/**
 * Get the absolute canonical path, falling back to the raw path on failure.
 *
 * **WARNING**: Resolving symlinks can produce a path DIFFERENT from the one used to write
 * a file, causing subsequent [exists] calls to return false on Linux systems where
 * TMPDIR or other directories use symlinks. Use it where a canonical path is wanted (path-traversal
 * validation in GlobExpander, cache file locations). For user-provided file paths, use [resolveUserPath].
 *
 * Falls back to [this] unchanged if resolution fails (e.g., TMPDIR-relative roots in CI).
 */
@Suppress("TooGenericExceptionCaught")
fun Path.absolutePathOrSelf(): Path =
    try {
        SystemFileSystem.resolve(this)
    } catch (_: Exception) {
        // boundary: filesystem probe, any failure means the fallback value
        this
    }

/**
 * Get the file extension (without the dot).
 */
fun Path.extension(): String = safeName.substringAfterLast('.', "")

/**
 * Parent of this path, same result as [Path.parent]. Use this instead: on Apple targets kotlinx-io computes
 * [Path.parent] and [Path.name] with libc `dirname()`/`basename()`, which return one buffer shared by every thread,
 * so two concurrent calls can read each other's (or a half-written) result.
 */
expect val Path.safeParent: Path?

/** Last segment of this path, same result as [Path.name]; thread-safe, see [safeParent]. */
expect val Path.safeName: String

/** [Path.parent] for a `/`-separated path already stripped of trailing separators, without libc. */
internal fun unixParent(path: String): String? {
    val slash = path.lastIndexOf('/')
    if (slash < 0) return null
    val parent = path.substring(0, slash).trimEnd('/').ifEmpty { "/" }
    return parent.takeIf { it != path }
}

/** [Path.name] for a `/`-separated path already stripped of trailing separators, without libc. */
internal fun unixName(path: String): String = if (path == "/") "" else path.substringAfterLast('/')

/**
 * Read all bytes from a file using buffered I/O.
 */
fun Path.readBytes(): ByteArray =
    SystemFileSystem.source(this).buffered().use { source ->
        source.readByteArray()
    }

/**
 * Write bytes to a file using buffered I/O.
 */
fun Path.writeBytes(data: ByteArray) {
    SystemFileSystem.sink(this).buffered().use { sink ->
        sink.write(data)
        sink.flush()
    }
}

/**
 * Copy a file from this path to the destination.
 *
 * @param destination The target path.
 * @param overwrite Whether to overwrite if the destination exists.
 */
fun Path.copyTo(
    destination: Path,
    overwrite: Boolean = false,
) {
    if (!overwrite && SystemFileSystem.exists(destination)) {
        error("Destination file already exists: $destination")
    }
    val data = this.readBytes()
    destination.writeBytes(data)
}

/**
 * Get the file size in bytes.
 *
 * Used by `LocalContentReader` to enforce the file size cap.
 */
fun Path.fileSize(): Long {
    val metadata = SystemFileSystem.metadataOrNull(this)
        ?: error("File does not exist: $this")
    return metadata.size
}

/**
 * Delete a file if it exists.
 *
 * @return true if the file was deleted, false if it didn't exist.
 */
fun deleteIfExists(path: Path): Boolean {
    if (!SystemFileSystem.exists(path)) {
        return false
    }
    SystemFileSystem.delete(path)
    return true
}

/**
 * Create directories (including parent directories) if they don't exist. Safe against a concurrent creator: a
 * failure is ignored when the directory exists afterwards (the native `mkdir` reports `File exists` on that race).
 *
 * @return true if directories were created, false if they already existed.
 */
fun createDirectories(path: Path): Boolean = createDirectories(path) { createDirectoriesRaw(it) }

/** Creates [path] and its missing ancestors; on native without kotlinx-io's racy `Path.parent` (see [safeParent]). */
internal expect fun createDirectoriesRaw(path: Path)

/** [createDirectories] with the creating call injected, so a test can stage the concurrent-creator race. */
internal fun createDirectories(
    path: Path,
    create: (Path) -> Unit,
): Boolean {
    if (SystemFileSystem.exists(path)) {
        return false
    }
    return try {
        create(path)
        true
    } catch (e: IOException) {
        path.isDirectory() || throw e
    }
}

/**
 * Move/rename a file from source to destination.
 *
 * @param from Source path.
 * @param to Destination path.
 * @param overwrite Whether to overwrite if the destination exists.
 */
fun moveFile(
    from: Path,
    to: Path,
    overwrite: Boolean = false,
) {
    if (!overwrite && SystemFileSystem.exists(to)) {
        error("Destination file already exists: $to")
    }
    SystemFileSystem.atomicMove(from, to)
}

/**
 * Writes [data] to a temp file next to this path, then renames it over this path: a reader sees the old file or the
 * new one, never a partial write, and concurrent writers never share a temp file. A failed write removes the temp file.
 * The temp name starts with `.` and ends with `.tmp`.
 */
fun Path.writeBytesAtomic(
    data: ByteArray,
    fs: FileSystem = SystemFileSystem,
) = writeBytesAtomic(data, fs, Random)

/** [writeBytesAtomic] with the [random] source of the temp name, so a test can predict it. */
internal fun Path.writeBytesAtomic(
    data: ByteArray,
    fs: FileSystem,
    random: Random,
) {
    val temp = Path(safeParent ?: Path("."), ".$safeName.${random.nextLong().toULong().toString(HEX_RADIX)}.tmp")
    try {
        fs.sink(temp).buffered().use { it.write(data) }
        fs.atomicMove(temp, this)
    } catch (e: IOException) {
        try {
            fs.delete(temp, mustExist = false)
        } catch (cleanup: IOException) {
            e.addSuppressed(cleanup)
        }
        throw e
    }
}

/** [writeBytesAtomic] for UTF-8 [text]. */
fun Path.writeTextAtomic(
    text: String,
    fs: FileSystem = SystemFileSystem,
) = writeBytesAtomic(text.encodeToByteArray(), fs)

private const val HEX_RADIX = 16

/**
 * Get last modified time in milliseconds. Platform-specific implementation.
 *
 * Returns 0 if the file doesn't exist or metadata is unavailable.
 */
expect fun Path.lastModifiedMillis(): Long

/**
 * Returns true if [path] can be opened for reading.
 *
 * Uses a probe open: [kotlinx.io.files.FileSystem.source] throws for permission
 * errors on all platforms, so no platform-specific access() call is needed.
 */
@Suppress("TooGenericExceptionCaught")
fun canRead(path: Path): Boolean =
    try {
        SystemFileSystem.source(path).buffered().use { true }
    } catch (_: Exception) {
        // boundary: filesystem probe, any failure means the fallback value
        false
    }

private const val MAX_TEMP_FILE_ATTEMPTS = 100
private val WINDOWS_ABSOLUTE_PATH_REGEX = Regex("^[A-Za-z]:[\\\\/].*")

/**
 * Atomically creates an empty file at [path] readable and writable by the owner only (0600 on POSIX).
 *
 * @return `false` when [path] already exists; any other failure is an error.
 */
internal expect fun createFileExclusive(path: Path): Boolean

/**
 * Creates a unique empty temporary file in [directory], owner-only (0600 on POSIX).
 *
 * The name carries a random part and the file is created with an exclusive create (O_CREAT|O_EXCL), so a file
 * planted at a guessed name is never reused.
 *
 * @param directory Directory to create the temp file in.
 * @param prefix Prefix for the file name.
 * @param suffix Suffix for the file name.
 * @return Path to the newly created temporary file.
 */
fun createTempFileInDir(
    directory: Path,
    prefix: String,
    suffix: String,
): Path = createTempFileInDir(directory, prefix, suffix, Random)

/** [createTempFileInDir] with the name source injected, so tests can force every attempt to collide. */
internal fun createTempFileInDir(
    directory: Path,
    prefix: String,
    suffix: String,
    random: Random,
): Path {
    repeat(MAX_TEMP_FILE_ATTEMPTS) {
        val path = Path(directory.toString(), "$prefix${random.nextLong().toULong().toString(radix = 36)}$suffix")
        if (createFileExclusive(path)) return path
    }
    error("Failed to create unique temp file in $directory after $MAX_TEMP_FILE_ATTEMPTS attempts")
}

/**
 * Resolve a user-supplied path string against a base directory WITHOUT calling realpath.
 *
 * Both [SystemFileSystem.resolve] and POSIX realpath() resolve ALL symbolic links,
 * which can return a path different from the one used to create the file. This function
 * avoids that by building the path directly without canonicalization.
 *
 * @param pathString Absolute or relative path from user config/flags.
 * @param basePath Base directory for resolving relative paths.
 * @return A [Path] suitable for [exists], [readText], [canRead] etc.
 */
fun resolveUserPath(
    pathString: String,
    basePath: Path,
): Path = if (isAbsolutePath(pathString)) Path(pathString) else Path(basePath.toString(), pathString)

/**
 * Returns true if [path] is absolute on the current OS.
 *
 * Handles Unix (leading `/`), Windows (`C:\`, `C:/`) and UNC (`\\server\share`) paths.
 */
fun isAbsolutePath(path: String): Boolean = path.startsWith("/") || path.startsWith("\\\\") || WINDOWS_ABSOLUTE_PATH_REGEX.matches(path)
