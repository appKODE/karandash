package io.karandash.providers.common

/**
 * Validates URL path segments to prevent path traversal attacks.
 *
 * User-supplied values interpolated into API URL paths (e.g., `owner`, `repo`)
 * must not contain path separator or traversal characters.
 */
object PathSegmentEncoder {
    /**
     * Validates that [segment] is a safe URL path segment.
     *
     * @param segment The path segment to validate (e.g., repository owner or name).
     * @return The validated segment unchanged.
     * @throws IllegalArgumentException if the segment is blank, contains `/`, or contains `..`.
     */
    fun encode(segment: String): String {
        require(segment.isNotBlank()) { "Path segment cannot be blank" }
        require(!segment.contains('/')) { "Path segment cannot contain '/': $segment" }
        require(!segment.contains("..")) { "Path segment cannot contain '..': $segment" }
        return segment
    }
}
