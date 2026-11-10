package io.karandash.wasm

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Timeout guard shared by every runtime. On native the guard runs each call on a worker
 * and, on timeout, interrupts the engine (Chasm stops the guest) and abandons the call.
 */
@OptIn(ExperimentalAtomicApi::class)
class TimedWasmEngineTest {
    /** Stands in for a runtime stuck in guest code: `spin` loops until [release] is set. */
    private class SpinningEngine : WasmEngine {
        val release = AtomicBoolean(false)
        val closes = AtomicInt(0)

        override fun callI32Array(
            funcName: String,
            args: IntArray,
        ): Int {
            if (funcName == "spin") {
                while (!release.load()) {
                    // guest infinite loop
                }
            }
            return args.sum() + 1
        }

        override fun readMemory(
            offset: Int,
            length: Int,
        ): ByteArray = ByteArray(length)

        override fun writeMemory(
            offset: Int,
            data: ByteArray,
        ) = Unit

        override fun close() {
            closes.incrementAndFetch()
        }
    }

    @Test
    fun fastCallsPassThrough() {
        val delegate = SpinningEngine()
        val engine = TimedWasmEngine(delegate, GuestCallRunner(), 5.seconds) {}
        assertEquals(6, engine.callI32("add", 2, 3))
        assertFalse(engine.isAbandoned)
        engine.close()
        assertEquals(1, delegate.closes.load())
    }

    @Test
    fun guestExceptionPropagates() {
        val engine = TimedWasmEngine(ThrowingEngine, GuestCallRunner(), 5.seconds) {}
        val error = assertFailsWith<IllegalArgumentException> { engine.callI32("boom") }
        assertEquals("boom", error.message)
        assertFalse(engine.isAbandoned)
        engine.close()
    }

    @Test
    fun hungCallTimesOutAndEngineIsAbandoned() {
        val delegate = SpinningEngine()
        val warnings = mutableListOf<String>()
        val engine = TimedWasmEngine(delegate, GuestCallRunner(), 200.milliseconds) { warnings += it }

        val start = TimeSource.Monotonic.markNow()
        assertFailsWith<IllegalStateException> { engine.callI32("spin") }
        assertTrue(start.elapsedNow() < 5.seconds, "timeout took ${start.elapsedNow()}")
        assertTrue(engine.isAbandoned)
        assertEquals(1, warnings.size)
        assertTrue("'spin' exceeded" in warnings.single(), warnings.single())

        assertFailsWith<IllegalStateException> { engine.callI32("add", 1) }
        assertFailsWith<IllegalStateException> { engine.readMemory(0, 1) }
        engine.close()
        assertEquals(0, delegate.closes.load(), "a runtime still running guest code must not be freed")
        delegate.release.store(true)
    }

    private object ThrowingEngine : WasmEngine {
        override fun callI32Array(
            funcName: String,
            args: IntArray,
        ): Int = throw IllegalArgumentException(funcName)

        override fun readMemory(
            offset: Int,
            length: Int,
        ): ByteArray = ByteArray(length)

        override fun writeMemory(
            offset: Int,
            data: ByteArray,
        ) = Unit

        override fun close() = Unit
    }
}
