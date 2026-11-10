package io.karandash.api.exception

/**
 * Thrown when a plugin fails security verification (checksum, signature, SSRF).
 */
public class PluginSecurityException(
    message: String,
    cause: Throwable? = null,
) : KarandashException(message, cause)
