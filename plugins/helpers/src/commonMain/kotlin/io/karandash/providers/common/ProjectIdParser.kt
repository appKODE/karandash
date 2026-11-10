package io.karandash.providers.common

/**
 * Utility for parsing structured project identifiers used across provider modules.
 *
 * Provider modules receive project IDs as flat strings with a platform-specific format
 * (e.g. "owner/repo" for GitHub, "org/project/repo" for Azure DevOps).
 * This object centralises the split-and-validate logic.
 */
object ProjectIdParser {
    /**
     * Parse a two-part project identifier.
     *
     * @param projectId The raw project ID string
     * @param format Human-readable description of the expected format (for error messages)
     * @return A [Pair] of the two parts
     * @throws IllegalArgumentException if the ID does not contain exactly two segments
     */
    fun parsePair(
        projectId: String,
        format: String = "owner/repo"
    ): Pair<String, String> {
        val parts = projectId.split("/")
        require(parts.size == 2) { "Invalid project ID format: expected '$format', got: $projectId" }
        return parts[0] to parts[1]
    }

    /**
     * Parse a three-part project identifier.
     *
     * @param projectId The raw project ID string
     * @param format Human-readable description of the expected format (for error messages)
     * @return A [Triple] of the three parts
     * @throws IllegalArgumentException if the ID does not contain exactly three segments
     */
    fun parseTriple(
        projectId: String,
        format: String = "org/project/repo"
    ): Triple<String, String, String> {
        val parts = projectId.split("/")
        require(parts.size == 3) { "Invalid project ID format: expected '$format', got: $projectId" }
        return Triple(parts[0], parts[1], parts[2])
    }
}
