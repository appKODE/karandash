package io.karandash.manifest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MissingPluginBehaviorTest {
    @Test
    fun `fromString fail returns FAIL`() {
        assertEquals(MissingPluginBehavior.FAIL, MissingPluginBehavior.fromString("fail"))
    }

    @Test
    fun `fromString warn returns WARN`() {
        assertEquals(MissingPluginBehavior.WARN, MissingPluginBehavior.fromString("warn"))
    }

    @Test
    fun `fromString ignore returns IGNORE`() {
        assertEquals(MissingPluginBehavior.IGNORE, MissingPluginBehavior.fromString("ignore"))
    }

    @Test
    fun `fromString uppercase FAIL returns FAIL`() {
        assertEquals(MissingPluginBehavior.FAIL, MissingPluginBehavior.fromString("FAIL"))
    }

    @Test
    fun `fromString mixed case Warn returns WARN`() {
        assertEquals(MissingPluginBehavior.WARN, MissingPluginBehavior.fromString("Warn"))
    }

    @Test
    fun `fromString with surrounding spaces returns correct value`() {
        assertEquals(MissingPluginBehavior.FAIL, MissingPluginBehavior.fromString("  fail  "))
    }

    @Test
    fun `fromString unknown returns null`() {
        assertNull(MissingPluginBehavior.fromString("unknown"))
    }

    @Test
    fun `fromString empty string returns null`() {
        assertNull(MissingPluginBehavior.fromString(""))
    }
}
