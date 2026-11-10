package io.karandash.api.exception

/**
 * Security exception for plugin registry operations (e.g. a non-HTTPS registry URL).
 */
public class RegistrySecurityException(
    message: String,
) : KarandashException(message)
