package io.karandash.cli

import io.karandash.config.TimeoutConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalTimeoutResolutionTest {
    @Test
    fun `invalid global timeout warns and falls back to default`() {
        val warnings = mutableListOf<String>()

        val ms = resolveGlobalTimeoutMs(arrayOf("validate", "--global-timeout", "not-a-duration")) { warnings += it }

        assertEquals(TimeoutConfig.DEFAULT_GLOBAL.millis, ms)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().startsWith("[WARN]"), warnings.single())
        assertTrue("not-a-duration" in warnings.single(), warnings.single())
    }

    @Test
    fun `valid global timeout does not warn`() {
        val warnings = mutableListOf<String>()

        val ms = resolveGlobalTimeoutMs(arrayOf("--global-timeout=30s")) { warnings += it }

        assertEquals(30_000L, ms)
        assertTrue(warnings.isEmpty())
    }
}
