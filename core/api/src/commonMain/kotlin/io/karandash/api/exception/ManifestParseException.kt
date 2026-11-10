package io.karandash.api.exception

/**
 * Thrown when a plugin manifest cannot be parsed or contains invalid data.
 *
 * @param message Description of the parse failure.
 * @param cause Optional underlying exception.
 */
public class ManifestParseException(
    message: String,
    cause: Throwable? = null,
) : KarandashException(message, cause)
