package io.karandash.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleExecutionMetaTest {
    @Test
    fun `default priority is 100`() {
        assertEquals(100, RuleExecutionMeta().priority)
    }

    @Test
    fun `default bailOnFail is false`() {
        assertFalse(RuleExecutionMeta().bailOnFail)
    }

    @Test
    fun `priority 1 is valid`() {
        assertEquals(1, RuleExecutionMeta(priority = 1).priority)
    }

    @Test
    fun `priority 999 is valid`() {
        assertEquals(999, RuleExecutionMeta(priority = 999).priority)
    }

    @Test
    fun `priority 0 throws`() {
        assertFailsWith<IllegalArgumentException> {
            RuleExecutionMeta(priority = 0)
        }
    }

    @Test
    fun `priority 1000 throws`() {
        assertFailsWith<IllegalArgumentException> {
            RuleExecutionMeta(priority = 1000)
        }
    }

    @Test
    fun `negative priority throws`() {
        assertFailsWith<IllegalArgumentException> {
            RuleExecutionMeta(priority = -1)
        }
    }

    @Test
    fun `bailOnFail true is stored`() {
        assertTrue(RuleExecutionMeta(bailOnFail = true).bailOnFail)
    }

    @Test
    fun `when defaults to null`() {
        val meta = RuleExecutionMeta()
        assertNull(meta.`when`)
    }

    @Test
    fun `when can be set with expression only`() {
        val whenConfig = WhenConfig(expression = "not(is_draft)")
        val meta = RuleExecutionMeta(`when` = whenConfig)
        assertEquals("not(is_draft)", meta.`when`?.expression)
        assertNull(meta.`when`?.override)
    }

    @Test
    fun `mode defaults to NORMAL`() {
        val meta = RuleExecutionMeta()
        assertEquals(RuleMode.NORMAL, meta.mode)
    }

    @Test
    fun `mode can be set to SUPPLEMENT`() {
        val meta = RuleExecutionMeta(mode = RuleMode.SUPPLEMENT)
        assertEquals(RuleMode.SUPPLEMENT, meta.mode)
    }

    @Test
    fun `mode can be set to FALLBACK`() {
        val meta = RuleExecutionMeta(mode = RuleMode.FALLBACK)
        assertEquals(RuleMode.FALLBACK, meta.mode)
    }

    @Test
    fun `concernArea defaults to null`() {
        val meta = RuleExecutionMeta()
        assertNull(meta.concernArea)
    }

    @Test
    fun `concernArea can be set`() {
        val meta = RuleExecutionMeta(mode = RuleMode.FALLBACK, concernArea = "ticket-reference")
        assertEquals("ticket-reference", meta.concernArea)
    }
}
