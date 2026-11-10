package io.karandash.watcher

internal interface PlatformFileWatcherDelegate {
    fun startWatching(onChange: () -> Unit)

    fun stop()

    fun isSupported(): Boolean
}

internal expect fun createPlatformFileWatcherDelegate(filePath: String): PlatformFileWatcherDelegate

class PlatformFileWatcher constructor(
    filePath: String
) {
    private val delegate = createPlatformFileWatcherDelegate(filePath)

    fun startWatching(onChange: () -> Unit) {
        delegate.startWatching(onChange)
    }

    fun stop() {
        delegate.stop()
    }

    fun isSupported(): Boolean = delegate.isSupported()
}
