package io.karandash.api.exception

/**
 * Thrown when a plugin binary cannot be loaded (e.g. a native library missing a required ABI symbol).
 *
 * Common to every target.
 */
public class PluginLoadException(
    message: String,
) : KarandashException(message)
