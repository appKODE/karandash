package io.karandash.bootstrap

import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlin.concurrent.Volatile

/**
 * Logger that delegates to a mutable inner reference.
 *
 * Created once and shared across components that need a stable logger reference.
 * The actual delegate is configured later via [configure] once CLI flags are parsed.
 *
 * @param delegate Initial delegate logger. Defaults to [NoOpLogger].
 */
class DelegatingLogger(
    @Volatile private var delegate: Logger = NoOpLogger,
) : Logger {
    /** Replace the inner delegate (e.g. after CLI flags are parsed). */
    fun configure(logger: Logger) {
        delegate = logger
    }

    override fun debug(message: String) = delegate.debug(message)

    override fun info(message: String) = delegate.info(message)

    override fun warn(message: String) = delegate.warn(message)

    override fun error(message: String) = delegate.error(message)

    override fun error(
        message: String,
        throwable: Throwable,
    ) = delegate.error(message, throwable)

    override val isDebugEnabled: Boolean get() = delegate.isDebugEnabled

    /**
     * Context is resolved against the delegate at call time, so a contextual logger taken
     * before [configure] still follows later reconfiguration.
     */
    override fun withContext(
        key: String,
        value: String,
    ): Logger = ContextLogger(this, listOf(key to value))

    private class ContextLogger(
        private val root: DelegatingLogger,
        private val fields: List<Pair<String, String>>,
    ) : Logger {
        private fun target(): Logger = fields.fold(root.delegate) { logger, (key, value) -> logger.withContext(key, value) }

        override fun debug(message: String) = target().debug(message)

        override fun info(message: String) = target().info(message)

        override fun warn(message: String) = target().warn(message)

        override fun error(message: String) = target().error(message)

        override fun error(
            message: String,
            throwable: Throwable,
        ) = target().error(message, throwable)

        override val isDebugEnabled: Boolean get() = target().isDebugEnabled

        override fun withContext(
            key: String,
            value: String,
        ): Logger = ContextLogger(root, fields + (key to value))
    }
}
