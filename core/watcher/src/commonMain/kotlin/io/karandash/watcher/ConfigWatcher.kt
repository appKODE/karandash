package io.karandash.watcher

import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger

expect class ConfigWatcher(
    filePath: String,
    debounceMs: Long,
    logger: Logger = NoOpLogger,
    onChange: () -> Unit
) {
    fun start()

    fun stop()

    fun isSupported(): Boolean
}
