@file:OptIn(ObsoleteWorkersApi::class)

package io.karandash.loader.wasm

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kotlin.time.Duration

internal actual fun runAfter(
    delay: Duration,
    action: () -> Unit,
) {
    Worker.start(name = "karandash-wasm-watchdog").executeAfter(delay.inWholeMicroseconds, action)
}
