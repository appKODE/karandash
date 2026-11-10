package io.karandash.logger

/**
 * Logger that silently discards all messages.
 *
 * Used by plugin bridges (C ABI, WASM) and test fixtures where
 * log output is not observed or is captured through other means.
 */
object NoOpLogger : Logger {
    override fun debug(message: String) = Unit

    override fun info(message: String) = Unit

    override fun warn(message: String) = Unit

    override fun error(message: String) = Unit

    override val isDebugEnabled: Boolean get() = false
}
