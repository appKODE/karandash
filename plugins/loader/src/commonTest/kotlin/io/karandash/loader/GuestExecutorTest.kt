package io.karandash.loader

import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalAtomicApi::class)
class GuestExecutorTest {
    @Test
    fun `a call that finishes in time returns its value`() =
        runTest {
            val lane = GuestExecutor("test", 5.seconds, NoOpLogger)

            assertEquals(42, lane.run("answer") { 42 })
            assertFalse(lane.isHung)
        }

    @Test
    fun `a call past the timeout marks the guest hung and later calls are skipped`() =
        runTest {
            val release = AtomicBoolean(false)
            val laterCallRan = AtomicBoolean(false)
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            val lane = GuestExecutor("test", 100.milliseconds, NoOpLogger)

            val stuck =
                lane.run("stuck") {
                    while (!release.load() && deadline.hasNotPassedNow()) {
                        // simulates a guest that never returns
                    }
                    1
                }
            val later = lane.run("later") { laterCallRan.store(true) }
            release.store(true)

            assertNull(stuck)
            assertTrue(lane.isHung)
            assertNull(later)
            assertFalse(laterCallRan.load())
        }

    @Test
    fun `time spent in host calls extends the deadline`() =
        runTest {
            val started = TimeSource.Monotonic.markNow()
            // Everything after the first 50 ms counts as host time, so the 100 ms budget never runs out.
            val lane = GuestExecutor("test", 100.milliseconds, NoOpLogger) { (started.elapsedNow() - 50.milliseconds).coerceAtLeast(ZERO) }
            val busyUntil = started + 300.milliseconds

            val result =
                lane.run("slow") {
                    while (busyUntil.hasNotPassedNow()) {
                        // a guest waiting on its host call
                    }
                    7
                }

            assertEquals(7, result)
            assertFalse(lane.isHung)
        }
}
