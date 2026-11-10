package io.karandash.api.exception

/**
 * Thrown when a module referenced in config cannot be resolved.
 *
 * @param message Description of the missing module.
 * @param moduleId The unresolved module identifier.
 * @param suggestions Possible alternative module IDs (from fuzzy matching).
 */
public class MissingModuleException(
    message: String,
    public val moduleId: String,
    public val suggestions: List<String> = emptyList(),
) : KarandashException(message)
