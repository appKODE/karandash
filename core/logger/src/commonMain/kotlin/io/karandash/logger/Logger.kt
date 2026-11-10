package io.karandash.logger

/**
 * Logging abstraction used throughout the Karandash pipeline.
 *
 * Implementations control output destination and verbosity filtering:
 * - [debug] messages appear only when verbose mode is enabled
 * - [info] messages appear during normal operation (suppressed by quiet mode)
 * - [warn] messages appear unless quiet mode is enabled
 * - [error] messages always appear regardless of mode
 */
interface Logger {
    /** Verbose-only diagnostic message. Only shown when `--verbose` is active. */
    fun debug(message: String)

    /** Normal operational message. Suppressed by `--quiet`. */
    fun info(message: String)

    /** Warning message. Suppressed by `--quiet`. */
    fun warn(message: String)

    /** Error message. Always shown regardless of quiet/verbose settings. */
    fun error(message: String)

    /**
     * Error with its cause. The default appends the exception type and message; loggers may add
     * the stack trace (the CLI logger does in verbose mode).
     */
    fun error(
        message: String,
        throwable: Throwable,
    ) = error("$message: ${throwable::class.simpleName}: ${throwable.message}")

    /**
     * Whether [debug] output is shown. Lets callers skip building expensive messages - see the
     * `debug { ... }` extension. Defaults to `false`: a logger that shows debug output overrides it.
     */
    val isDebugEnabled: Boolean get() = false

    /**
     * Returns a logger that prepends [key]=[value] context to every message.
     *
     * Context accumulates - nested calls add keys:
     * ```
     * logger.withContext("rule", "title-format").withContext("mr", "123").warn("Pattern too long")
     * // outputs: [rule=title-format mr=123] Pattern too long
     * ```
     *
     * Default implementation returns `this` (no allocation) for loggers that don't support context.
     */
    fun withContext(
        key: String,
        value: String
    ): Logger = this

    /**
     * Returns a logger with multiple context key-value pairs prepended.
     * @see withContext
     */
    fun withContext(vararg pairs: Pair<String, String>): Logger {
        var result: Logger = this
        for ((key, value) in pairs) {
            result = result.withContext(key, value)
        }
        return result
    }
}

/** Logs the message built by [message] at debug level, building it only when [Logger.isDebugEnabled]. */
inline fun Logger.debug(message: () -> String) {
    if (isDebugEnabled) debug(message())
}
