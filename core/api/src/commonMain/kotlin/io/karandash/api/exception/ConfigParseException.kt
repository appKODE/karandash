package io.karandash.api.exception

/**
 * Thrown when a Karandash configuration cannot be parsed or interpreted.
 * This is a user-facing error (bad configuration) rather than an internal bug,
 * so callers should display the message directly to the user.
 *
 * @property hint one imperative action that fixes the config, printed after the message; null when there is none.
 */
public class ConfigParseException(
    message: String,
    public val hint: String? = null,
) : KarandashException(message)
