package io.karandash.api.errors

import io.karandash.api.exception.KarandashException

/**
 * The config cannot be loaded.
 *
 * @property hint one imperative action that fixes the config, printed after the message; null when there is none.
 */
public open class ConfigException(
    message: String,
    cause: Throwable? = null,
    public val hint: String? = null,
) : KarandashException(message, cause)

/**
 * The `extends:` chain cannot be built: a cycle, the depth or the size limit. [error] says which, so callers branch
 * on it instead of the message; the message starts with its bracketed code.
 */
public class ConfigChainException(
    public val error: KarandashError,
    message: String,
) : ConfigException("${error.bracketCode} $message")

/**
 * The config references modules that are not installed and `plugins.on-missing` is `fail`. A [ConfigException], so
 * callers that only need "the config does not load" are unaffected; read-only commands catch it to report it instead
 * of falling back to defaults.
 */
public class MissingPluginException(
    message: String,
) : ConfigException(message)

public class ProviderException(
    message: String,
    cause: Throwable? = null,
) : KarandashException(message, cause)
