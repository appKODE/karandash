package io.karandash.api.errors

/**
 * Central catalog of all known error codes with metadata for enhanced error reporting.
 *
 * Delegates to [KarandashError] enum entries. This object is retained for backward
 * compatibility; prefer using [KarandashError] directly for new code.
 */
public object ErrorCatalog {
    /**
     * Look up an error code by its code string.
     * @param code The error code (e.g., "E0001")
     * @return The ErrorCode if found, null otherwise
     */
    public fun lookup(code: String): ErrorCode? = KarandashError.fromCode(code)?.toErrorCode()

    /**
     * Get all error codes for a specific category.
     * @param category The error category to filter by
     * @return List of error codes in the specified category
     */
    public fun byCategory(category: ErrorCategory): List<ErrorCode> = KarandashError.byCategory(category).map { it.toErrorCode() }

    /**
     * Format an error message by substituting template parameters.
     * @param code The error code to format
     * @param params Map of parameter names to values for template substitution
     * @return Formatted message with parameters substituted
     */
    public fun format(
        code: ErrorCode,
        params: Map<String, String>
    ): String {
        var message = code.messageTemplate
        params.forEach { (key, value) ->
            message = message.replace("{$key}", value)
        }
        return message
    }

    /**
     * Get all registered error codes.
     * @return List of all error codes
     */
    public fun all(): List<ErrorCode> = KarandashError.entries.map { it.toErrorCode() }

    /**
     * Get count of registered error codes.
     * @return Total number of error codes
     */
    public fun count(): Int = KarandashError.entries.size
}
