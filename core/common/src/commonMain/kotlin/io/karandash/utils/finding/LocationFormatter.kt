package io.karandash.utils.finding

import io.karandash.models.finding.Location

// Shared extension functions for formatting Location values into reporter-specific structures.

/**
 * Returns a human-readable string representation of this location.
 *
 * - [Location.FileRef]: `"path/to/file:42"` (line is optional)
 * - [Location.CommitRef]: `"commit:abc123"`
 */
fun Location.toDisplayString(): String =
    when (this) {
        is Location.FileRef -> if (line != null) "$path:$line" else path
        is Location.CommitRef -> "commit:$sha"
    }

/**
 * Returns file path and line number for inline annotation support.
 *
 * @return Pair of (filePath, line?) or null if this is not a [Location.FileRef].
 */
fun Location.toFilePosition(): Pair<String, Int?>? =
    when (this) {
        is Location.FileRef -> path to line
        is Location.CommitRef -> null
    }
