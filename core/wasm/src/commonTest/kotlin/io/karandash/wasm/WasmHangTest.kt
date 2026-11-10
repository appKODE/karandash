package io.karandash.wasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** An infinite loop in a WASM export must not hang the host when run through the timed engine (Chicory on JVM, Chasm on native). */
class WasmHangTest {
    @Test
    fun infiniteLoopTimesOutAndAbandonsEngine() {
        val messages = mutableListOf<String>()
        val engine = createWasmEngine(WasmTestModules.spinAndAnswer(), 2.seconds) { messages += it }
        assertEquals(42, engine.callI32("answer"))

        val start = TimeSource.Monotonic.markNow()
        val error = assertFailsWith<IllegalStateException> { engine.callI32("spin") }
        assertTrue(start.elapsedNow() < 15.seconds, "timeout took ${start.elapsedNow()}")
        assertTrue("'spin' exceeded" in error.message.orEmpty(), error.message)
        assertEquals(1, messages.size)
        assertTrue(engine.isAbandoned)

        // Later calls fail fast instead of queueing behind the abandoned guest.
        assertFailsWith<IllegalStateException> { engine.callI32("answer") }
        engine.close()
    }

    /**
     * The guard relies on this: after an overrun, interrupting the engine frees the guest thread for the next call.
     * Where the engine cannot be interrupted (macOS x64), the caller is still released but the thread stays abandoned.
     */
    @Test
    fun interruptFreesTheGuestThread() {
        val engine = createWasmEngine(WasmTestModules.spinAndAnswer())
        val runner = GuestCallRunner()
        assertNull(runner.runWithin(500.milliseconds) { engine.callI32("spin") })
        engine.interrupt()
        if (wasmEngineSupportsInterrupt) {
            assertEquals(42, runner.runWithin(5.seconds) { engine.callI32("answer") }?.getOrNull())
        } else {
            assertGuestThreadAbandoned(runner) { engine.callI32("answer") }
        }
        runner.close()
        engine.close()
    }

    @Test
    fun hangingStartFunctionTimesOut() {
        val messages = mutableListOf<String>()
        val start = TimeSource.Monotonic.markNow()
        val error =
            assertFailsWith<IllegalStateException> { createWasmEngine(WasmTestModules.spinningStart(), 1.seconds) { messages += it } }
        assertTrue(start.elapsedNow() < 15.seconds, "timeout took ${start.elapsedNow()}")
        assertTrue("start exceeded" in error.message.orEmpty(), error.message)
        assertEquals(1, messages.size)
    }

    /** After a start function overruns, interrupting it frees the guest thread for the next job. */
    @Test
    fun interruptStopsAHangingStartFunction() {
        val start = wasmEngineStart(WasmTestModules.spinningStart())
        val runner = GuestCallRunner()
        assertNull(runner.runWithin(500.milliseconds) { start.start() })
        start.interrupt()
        if (wasmEngineSupportsInterrupt) {
            assertEquals(42, runner.runWithin(5.seconds) { 42 }?.getOrNull())
        } else {
            assertGuestThreadAbandoned(runner) { 42 }
        }
        runner.close()
    }

    /** No interrupt (macOS x64): the next job queues behind the spinning guest, and the caller still gets control back. */
    private fun assertGuestThreadAbandoned(
        runner: GuestCallRunner,
        job: () -> Int,
    ) {
        val start = TimeSource.Monotonic.markNow()
        assertNull(runner.runWithin(500.milliseconds, block = job))
        assertTrue(start.elapsedNow() < 5.seconds, "caller held for ${start.elapsedNow()}")
    }
}
