package io.karandash.config

import io.karandash.duration.KarandashDuration
import io.karandash.platform.ProcessTimeouts
import kotlin.test.Test
import kotlin.test.assertEquals

/** the default script timeout is the shared subprocess bound, not a second literal. */
class ScriptEntryConfigTimeoutTest {
    @Test
    fun `the default script timeout is the shared default seconds`() {
        assertEquals(KarandashDuration.ofSeconds(ProcessTimeouts.DEFAULT_SECONDS.toLong()), ScriptEntryConfig.DEFAULT_TIMEOUT)
    }
}
