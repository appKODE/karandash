package io.karandash.loader.wasm

import kotlin.time.Duration

internal actual fun runAfter(
    delay: Duration,
    action: () -> Unit,
) {
    Thread {
        Thread.sleep(delay.inWholeMilliseconds)
        action()
    }.apply { isDaemon = true }.start()
}
