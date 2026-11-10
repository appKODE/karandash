package io.karandash.cli.commands.validate

import io.karandash.config.pipeline.ConfigChain
import io.karandash.logger.Logger
import io.karandash.watcher.ConfigWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Local files of the resolved `extends` chain, deepest ancestor first; `http(s)` entries cannot be watched. */
internal fun ConfigChain.localFiles(): List<String> =
    entries.map { it.source.url }.filterNot { it.startsWith("http://") || it.startsWith("https://") }

/**
 * One [ConfigWatcher] per file of the `extends` chain, so a change to any layer calls [onChange].
 * [update] reconciles the watched set with a freshly resolved chain: new files get a watcher, dropped files lose it.
 *
 * @lifecycle per-run -- [update] is called serially (first by the command, then once per rerun inside `SingleFlight`)
 */
internal class ChainWatch(
    private val scope: CoroutineScope,
    private val debounceMs: Long,
    private val logger: Logger,
    private val onChange: () -> Unit,
) {
    private var watching: Map<String, Job> = emptyMap()

    fun update(files: Set<String>) {
        (watching.keys - files).forEach { watching.getValue(it).cancel() }
        val added = (files - watching.keys).associateWith(::watchFile)
        watching = watching.filterKeys { it in files } + added
    }

    private fun watchFile(path: String): Job =
        scope.launch {
            val watcher = ConfigWatcher(path, debounceMs, logger) { onChange() }
            if (watcher.isSupported()) {
                watcher.watchUntilCancelled()
            } else {
                logger.error("File watching is not supported on this platform")
            }
        }
}
