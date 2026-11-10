package io.karandash.loader.js

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class JsCallGuardTest {
    @Test
    fun cancelledCallerStillWaitsForTheInFlightCall() =
        runTest {
            val guard = JsCallGuard(timeout = 10.seconds)
            val callDone = CompletableDeferred<Unit>()

            val result = withContext(Dispatchers.Default) {
                withTimeoutOrNull(50.milliseconds) {
                    guard.run("slow") {
                        delay(500.milliseconds)
                        callDone.complete(Unit)
                    }
                }
            }

            assertNull(result, "the caller's own timeout still applies")
            assertTrue(callDone.isCompleted, "caller returned while the guest call was still running")
            assertFalse(guard.isHung)
        }
}
