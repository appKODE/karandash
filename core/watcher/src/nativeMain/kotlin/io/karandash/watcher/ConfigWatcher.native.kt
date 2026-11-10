package io.karandash.watcher

import io.karandash.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Wraps [PlatformFileWatcher] with debouncing to prevent rapid re-validation
 * when config files are saved multiple times in quick succession.
 */
actual class ConfigWatcher actual constructor(
    filePath: String,
    private val debounceMs: Long,
    private val logger: Logger,
    private val onChange: () -> Unit
) {
    private val fileWatcher = PlatformFileWatcher(filePath)
    private var debounceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Starts watching the config file. Blocks until [stop] is called.
     * Debounces [onChange] callback by [debounceMs] milliseconds.
     */
    @Suppress("TooGenericExceptionCaught") // Callback boundary: must not crash watcher
    actual fun start() {
        fileWatcher.startWatching {
            // Cancel previous debounce job if still running
            debounceJob?.cancel()

            // Schedule new onChange after debounce delay
            debounceJob = scope.launch {
                try {
                    delay(debounceMs)
                    onChange()
                } catch (_: CancellationException) {
                    // Expected when debounce is cancelled
                } catch (e: Exception) {
                    // boundary: a failing callback must not stop the watcher
                    logger.warn("Error during config reload: ${e.message}")
                }
            }
        }
    }

    /**
     * Stops watching the config file. Thread-safe.
     */
    actual fun stop() {
        debounceJob?.cancel()
        fileWatcher.stop()
        scope.cancel()
    }

    /**
     * Returns whether file watching is supported on this platform.
     */
    actual fun isSupported(): Boolean = fileWatcher.isSupported()
}
