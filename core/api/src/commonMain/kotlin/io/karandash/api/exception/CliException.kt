package io.karandash.api.exception

/**
 * CLI-specific exception base that carries an [exitCode] for process exit.
 *
 * Extends [KarandashException] so that generic `catch` blocks across module boundaries
 * can handle all Karandash errors uniformly.
 */
public sealed class CliException(
    message: String,
    cause: Throwable? = null,
    public val exitCode: Int,
) : KarandashException(message, cause)
