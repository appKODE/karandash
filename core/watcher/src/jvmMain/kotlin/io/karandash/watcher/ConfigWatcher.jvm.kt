package io.karandash.watcher

import io.karandash.logger.Logger
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit

/**
 * Watches a configuration file for modifications and triggers a callback
 * with debouncing to coalesce rapid changes.
 *
 * Uses Java's WatchService API to monitor the parent directory for MODIFY and CREATE events (atomic saves rename a temp file over the target)
 * on the target file. A 500ms debounce window makes bursts of file system
 * events (common during saves) result in a single callback invocation.
 */
actual class ConfigWatcher actual constructor(
    filePath: String,
    private val debounceMs: Long,
    private val logger: Logger,
    private val onChange: () -> Unit
) {
    private val configPath: Path = Path.of(filePath).toAbsolutePath()
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()

    @Volatile
    private var running = false

    private var debounceTimer: Timer? = null
    private val timerLock = Any()

    /**
     * Start watching for changes. This method blocks until [stop] is called
     * or the thread is interrupted.
     */
    actual fun start() {
        val dir = configPath.parent
            ?: throw IllegalArgumentException("Config path must have a parent directory: $configPath")
        val fileName = configPath.fileName

        // Use HIGH sensitivity to reduce polling interval on macOS (default ~10s -> ~2s)
        try {
            val highSensitivity = com.sun.nio.file.SensitivityWatchEventModifier.HIGH
            dir.register(watchService, arrayOf(StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE), highSensitivity)
        } catch (_: Exception) {
            // boundary: best-effort watch service setup and teardown on a blocking thread
            dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE)
        }
        running = true

        while (running) {
            val key = try {
                watchService.poll(1, TimeUnit.SECONDS)
            } catch (_: java.nio.file.ClosedWatchServiceException) {
                break
            } catch (_: InterruptedException) {
                break
            } ?: continue

            for (event in key.pollEvents()) {
                val changed = event.context() as? Path ?: continue
                if (changed.fileName == fileName) {
                    triggerDebounced()
                }
            }

            if (!key.reset()) {
                break
            }
        }
    }

    /**
     * Stop the watcher and release resources.
     */
    actual fun stop() {
        running = false
        synchronized(timerLock) {
            debounceTimer?.cancel()
            debounceTimer = null
        }
        try {
            watchService.close()
        } catch (_: Exception) {
            // boundary: best-effort watch service setup and teardown on a blocking thread
            // Ignore close errors
        }
    }

    /**
     * Returns whether file watching is supported on this platform.
     */
    actual fun isSupported(): Boolean = true

    /**
     * Schedule the onChange callback after the debounce window.
     * Resets the timer if called again before it fires.
     */
    @Suppress("TooGenericExceptionCaught") // Callback boundary: must not crash watcher
    private fun triggerDebounced() {
        synchronized(timerLock) {
            debounceTimer?.cancel()
            debounceTimer = Timer("config-watcher-debounce", true).apply {
                schedule(
                    object : TimerTask() {
                        override fun run() {
                            if (running) {
                                try {
                                    onChange()
                                } catch (e: Exception) {
                                    // boundary: a failing callback must not stop the watcher
                                    logger.warn("Watch callback error: ${e.message}")
                                }
                            }
                        }
                    },
                    debounceMs
                )
            }
        }
    }
}
