package io.karandash.wasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class WasmHostImportsTest {
    @Test
    fun `guest calls the host and reads the response back`() {
        val engine = createWasmEngine(WasmTestModules.hostCall(), 5.seconds, hostCall = { service, request -> "$service:$request" })
        assertEquals(7, engine.callI32("ping"))
        assertEquals("echo:{}", engine.readMemory(64, 7).decodeToString())
        engine.close()
    }

    @Test
    fun `module without the imports still loads when the host offers them`() {
        val engine = createWasmEngine(WasmTestModules.add(), 5.seconds, hostCall = { _, _ -> "{}" })
        assertEquals(5, engine.callI32("add", 2, 3))
        engine.close()
    }

    @Test
    fun `time spent in the host does not count against the guest call`() {
        val slowHost: WasmHostCall = { _, _ ->
            val started = TimeSource.Monotonic.markNow()
            while (started.elapsedNow() < 2.seconds) {
                // host work outlasting the guest-call timeout
            }
            "{}"
        }
        val engine = createWasmEngine(WasmTestModules.hostCall(), 1.seconds, hostCall = slowHost)
        assertEquals(2, engine.callI32("ping"))
        engine.close()
    }
}
