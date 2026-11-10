package io.karandash.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class GlobalWatchdogTest {
    @Test
    fun `returns the block result when it finishes in time`() =
        runTest {
            assertEquals(1, GlobalWatchdog(10.seconds, fixedByCli = false).run { 1 })
        }

    @Test
    fun `cancels the block and returns null when the timeout fires`() =
        runTest {
            assertNull(GlobalWatchdog(1.seconds, fixedByCli = false).run { delay(5.seconds) })
        }

    @Test
    fun `config re-arms a longer timeout`() =
        runTest {
            val result = GlobalWatchdog(1.seconds, fixedByCli = false).run {
                GlobalWatchdog.rearmActive(10.seconds)
                delay(5.seconds)
                1
            }
            assertEquals(1, result)
        }

    @Test
    fun `the CLI flag wins over the config`() =
        runTest {
            val result = GlobalWatchdog(1.seconds, fixedByCli = true).run {
                GlobalWatchdog.rearmActive(10.seconds)
                delay(5.seconds)
            }
            assertNull(result)
        }

    @Test
    fun `a zero timeout disables the watchdog`() =
        runTest {
            assertEquals(1, GlobalWatchdog(Duration.ZERO, fixedByCli = false).run { delay(1_000.seconds).let { 1 } })
        }
}
