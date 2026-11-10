package io.karandash.platform

/**
 * Validates arguments before passing them to [PlatformProcess] for git invocations.
 *
 * Karandash shells out to `git` from several subsystems (local-git provider,
 * local-git enrichment, convention detector, related-file finder). All user-derived
 * values (paths, symbol names, URLs) must flow through this sanitizer before being
 * attached to a command vector.
 *
 * Rejections throw [IllegalArgumentException] - callers should catch and either skip
 * the operation with a WARN log or translate to a user-facing error.
 *
 * This sanitizer does not understand shell syntax - commands are always passed as
 * argv lists to [PlatformProcess]. The checks guard against option injection (a path
 * starting with `-` is rejected; callers also put `--` or `--end-of-options` before
 * paths), log injection (newlines), and path traversal when a path is later stitched
 * into another string.
 */
object GitArgSanitizer {
    /**
     * Characters banned in any user-supplied argument. Newlines are rejected to keep
     * WARN logs un-spoofable; the remaining metacharacters catch accidental shell
     * escapes if a caller later string-joins the argument.
     */
    private val FORBIDDEN_CHARACTERS: CharArray = charArrayOf(
        ';',
        '|',
        '&',
        '$',
        '`',
        '>',
        '<',
        '(',
        ')',
        '{',
        '}',
        '!',
        '#',
        '\n',
        '\r',
        '\u0000',
    )

    /**
     * Validate and normalise a repo-relative path argument.
     *
     * @return the normalised path (Windows separators converted to `/`).
     * @throws IllegalArgumentException when [path] contains forbidden characters,
     *     is absolute, starts with `-` (git would read it as an option), or performs `..` traversal.
     */
    fun sanitizePath(path: String): String {
        requireSafe(path, "path")
        val normalised = path.replace('\\', '/')
        require(!isAbsolute(normalised)) { "Absolute path forbidden in git arg: $path" }
        require(!normalised.startsWith("-")) { "Path must not start with '-' in git arg: $path" }
        val segments = normalised.split('/')
        require(segments.none { it == ".." }) { "Path traversal forbidden in git arg: $path" }
        return normalised
    }

    /**
     * Validate a list of user-supplied arguments. Git flags (strings starting with
     * `-`) are accepted unchanged - callers are responsible for only allowing the
     * flags they intend.
     */
    fun sanitizeUserArgs(args: List<String>): List<String> {
        for (arg in args) requireSafe(arg, "argument")
        return args
    }

    /**
     * Validate that [path] stays within [repoRoot] once normalised. Used for
     * user-configured convention and context files before they are read by
     * `git show HEAD:<path>` or file IO.
     */
    fun validateWithinRepo(
        path: String,
        repoRoot: String
    ): String {
        val sanitized = sanitizePath(path)
        val normalisedRoot = repoRoot.replace('\\', '/').trimEnd('/')
        val joined = if (normalisedRoot.isEmpty()) sanitized else "$normalisedRoot/$sanitized"
        require(normalisedRoot.isEmpty() || joined.startsWith("$normalisedRoot/") || joined == normalisedRoot) {
            "Path escapes repository root: $path"
        }
        return sanitized
    }

    private fun requireSafe(
        value: String,
        kind: String
    ) {
        for (ch in FORBIDDEN_CHARACTERS) {
            require(ch !in value) {
                "Forbidden character ${describe(ch)} in git $kind: ${displaySafe(value)}"
            }
        }
    }

    private fun isAbsolute(path: String): Boolean {
        if (path.startsWith("/")) return true
        // Windows drive letter: C:\ or C:/ (already normalised with forward slashes).
        if (path.length >= 3 && path[1] == ':' && (path[2] == '/' || path[2] == '\\')) return true
        return false
    }

    private fun describe(ch: Char): String =
        when (ch) {
            '\n' -> "'\\n'"
            '\r' -> "'\\r'"
            '\u0000' -> "'\\u0000'"
            else -> "'$ch'"
        }

    /** Replace control characters so the violation can be safely logged. */
    private fun displaySafe(value: String): String {
        val sanitised = StringBuilder(value.length)
        for (ch in value) {
            sanitised.append(
                when {
                    ch == '\n' -> "\\n"
                    ch == '\r' -> "\\r"
                    ch == '\u0000' -> "\\u0000"
                    ch.isISOControl() -> "?"
                    else -> ch.toString()
                },
            )
        }
        return sanitised.toString()
    }
}
