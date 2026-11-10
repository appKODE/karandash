package io.karandash.plugins.ai.core

import kotlin.test.Test
import kotlin.test.assertEquals

class DiffModeTest {
    @Test
    fun `fromString parses CHANGED case-insensitively`() {
        assertEquals(DiffMode.CHANGED, DiffMode.fromString("changed"))
        assertEquals(DiffMode.CHANGED, DiffMode.fromString("CHANGED"))
        assertEquals(DiffMode.CHANGED, DiffMode.fromString("Changed"))
    }

    @Test
    fun `fromString parses PATCH`() {
        assertEquals(DiffMode.PATCH, DiffMode.fromString("patch"))
        assertEquals(DiffMode.PATCH, DiffMode.fromString("PATCH"))
    }

    @Test
    fun `fromString parses FULL`() {
        assertEquals(DiffMode.FULL, DiffMode.fromString("full"))
        assertEquals(DiffMode.FULL, DiffMode.fromString("FULL"))
    }

    @Test
    fun `fromString defaults to CHANGED for unknown values`() {
        assertEquals(DiffMode.CHANGED, DiffMode.fromString("unknown"))
        assertEquals(DiffMode.CHANGED, DiffMode.fromString(""))
        assertEquals(DiffMode.CHANGED, DiffMode.fromString("diff"))
    }
}
