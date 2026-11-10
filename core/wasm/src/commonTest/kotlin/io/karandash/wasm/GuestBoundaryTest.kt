package io.karandash.wasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** an Error thrown inside the guest, such as a stack overflow, reaches the caller as a failure. */
class GuestBoundaryTest {
    @Test
    fun `an Error in the guest call is carried back as a failure`() {
        val runner = GuestCallRunner()

        val result = runner.runWithin(5.seconds) { throw NotImplementedError("guest boom") }

        val failure = assertNotNull(assertNotNull(result).exceptionOrNull())
        assertTrue(failure is Error, failure.toString())
        assertEquals("guest boom", failure.message)

        // the guest thread survives and serves the next call
        assertEquals(7, runner.runWithin(5.seconds) { 7 }?.getOrNull())
        runner.close()
    }
}
