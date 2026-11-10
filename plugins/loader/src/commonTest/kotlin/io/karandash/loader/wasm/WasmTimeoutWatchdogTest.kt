package io.karandash.loader.wasm

import io.karandash.loader.RecordingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A timed-out guest the engine cannot stop ends the process after a grace period. */
class WasmTimeoutWatchdogTest {
    private val scheduled = mutableListOf<Duration>()
    private val exits = mutableListOf<Int>()

    private fun watchdog(
        logger: RecordingLogger,
        interrupts: Boolean,
    ) = WasmTimeoutWatchdog(
        logger,
        engineInterruptsGuests = interrupts,
        grace = 3.seconds,
        schedule = { delay, action ->
            scheduled += delay
            action()
        },
        exit = { exits += it },
    )

    @Test
    fun `an engine without interrupt exits with the execution error code after the grace period`() {
        val logger = RecordingLogger()
        watchdog(logger, interrupts = false).onTimeout("WASM guest call 'validate' exceeded 1s")

        assertEquals(listOf(3.seconds), scheduled)
        assertEquals(listOf(WasmTimeoutWatchdog.EXIT_CODE), exits)
        assertEquals(6, WasmTimeoutWatchdog.EXIT_CODE)
        assertEquals(listOf("WASM guest call 'validate' exceeded 1s"), logger.warnings)
        assertTrue(logger.errors.single().contains("cannot stop the guest"), logger.errors.toString())
    }

    @Test
    fun `an engine that interrupts its guest only warns`() {
        val logger = RecordingLogger()
        watchdog(logger, interrupts = true).onTimeout("WASM guest call 'validate' exceeded 1s")

        assertEquals(emptyList(), scheduled)
        assertEquals(emptyList(), exits)
        assertEquals(1, logger.warnings.size)
    }
}
