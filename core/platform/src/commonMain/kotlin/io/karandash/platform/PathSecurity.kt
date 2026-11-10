package io.karandash.platform

/**
 * Path normalization and traversal validation utilities.
 *
 * Used to prevent directory traversal attacks in file references,
 * remote config URLs, and glob patterns.
 */
object PathSecurity {
    private const val DRIVE_PREFIX_LENGTH = 3

    /**
     * Normalizes `.` and `..` segments in a URL path; a `..` above the root is dropped.
     *
     * @param path The URL path to normalize (e.g., `/a/b/../c` → `/a/c`).
     * @return The normalized path, always starting with `/`.
     */
    fun normalizePath(path: String): String = normalizeLexical("/$path")

    /**
     * The one lexical path normalizer: turns `\` into `/`, drops empty and `.` segments and collapses `..`.
     * A `/` or drive (`C:/`) root is kept and a `..` above it dropped; a `..` above a relative start is kept
     * (`../a` stays `../a`), so a path outside the start never compares equal to one inside it.
     */
    fun normalizeLexical(raw: String): String {
        val unified = raw.replace('\\', '/')
        val root = when {
            unified.startsWith("/") -> "/"
            unified.length >= DRIVE_PREFIX_LENGTH && unified[1] == ':' && unified[2] == '/' -> unified.take(DRIVE_PREFIX_LENGTH)
            else -> ""
        }
        val segments = ArrayDeque<String>()
        unified.removePrefix(root).split('/').forEach { segment ->
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." && segments.isNotEmpty() && segments.last() != ".." -> segments.removeLast()
                segment == ".." && root.isNotEmpty() -> Unit
                else -> segments.addLast(segment)
            }
        }
        return root + segments.joinToString("/")
    }

    /**
     * Extracts scheme + host + port (the "origin") from an HTTP(S) URL.
     *
     * @param url An HTTP(S) URL (e.g., `https://example.com:8080/path`).
     * @return The origin portion (e.g., `https://example.com:8080`).
     */
    fun extractOrigin(url: String): String {
        val protocolEnd = url.indexOf("://") + 3
        val hostEnd = url.indexOf('/', protocolEnd).let { if (it < 0) url.length else it }
        return url.substring(0, hostEnd)
    }

    /**
     * Extracts the path portion (after origin) from an HTTP(S) URL.
     *
     * @param url An HTTP(S) URL.
     * @return The path portion (e.g., `/path/to/resource`), or empty string if no path.
     */
    fun extractPath(url: String): String {
        val protocolEnd = url.indexOf("://") + 3
        val hostEnd = url.indexOf('/', protocolEnd).let { if (it < 0) url.length else it }
        return url.substring(hostEnd)
    }

    /**
     * Resolves a relative file path against a remote base URL.
     *
     * Handles `./` prefixes and `..` segments, producing a normalized absolute URL.
     *
     * @param baseUrl The base URL to resolve against.
     * @param relativePath The relative path to resolve.
     * @return Fully resolved, normalized URL.
     */
    fun resolveRemoteUrl(
        baseUrl: String,
        relativePath: String,
    ): String {
        val cleanRelative = relativePath.removePrefix("./")
        val raw = "$baseUrl/$cleanRelative"
        val origin = extractOrigin(raw)
        val path = extractPath(raw)
        return origin + normalizePath(path)
    }

    /**
     * Validates that a remote file path does not escape the base URL's origin or directory.
     *
     * @throws IllegalArgumentException if the path is absolute, an absolute URL,
     *   escapes the origin, or uses path traversal to escape the config directory.
     */
    fun validateRemoteFilePath(
        filePath: String,
        baseUrl: String,
    ) {
        require(!filePath.startsWith("http://") && !filePath.startsWith("https://")) {
            "Absolute URLs are not allowed in file references: $filePath"
        }
        require(!filePath.startsWith("/")) {
            "Absolute paths are not allowed in remote file references: $filePath"
        }

        val resolvedUrl = resolveRemoteUrl(baseUrl, filePath)
        val baseOrigin = extractOrigin(baseUrl)
        val resolvedOrigin = extractOrigin(resolvedUrl)
        require(baseOrigin == resolvedOrigin) {
            "Remote file reference escapes origin: $filePath (resolved to $resolvedUrl, expected origin $baseOrigin)"
        }

        val basePath = extractPath(baseUrl)
        val resolvedPath = extractPath(resolvedUrl)
        val normalizedBase = normalizePath(basePath)
        val normalizedResolved = normalizePath(resolvedPath)
        val baseDir = normalizedBase.substringBeforeLast('/')
        require(normalizedResolved == baseDir || normalizedResolved.startsWith("$baseDir/")) {
            "Remote file reference escapes config directory via path traversal: $filePath"
        }
    }
}
