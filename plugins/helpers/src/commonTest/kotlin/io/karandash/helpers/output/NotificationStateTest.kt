package io.karandash.helpers.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationStateTest {
    @Test
    fun `marker round-trips through parse`() {
        val state = NotificationState.of(false, listOf("b", "a")).copy(body = NotificationState.hash("body"))
        assertEquals(state, NotificationState.parse("summary text\n${state.toMarker()}"))
    }

    @Test
    fun `parse takes the last marker and ignores text without one`() {
        val first = NotificationState.of(true, emptyList())
        val last = NotificationState.of(false, listOf("x"))
        assertEquals(last, NotificationState.parse("${first.toMarker()}\n${last.toMarker()}"))
        assertNull(NotificationState.parse("no marker here"))
        assertNull(NotificationState.parse(null))
    }

    @Test
    fun `finding order does not change the state`() {
        assertTrue(NotificationState.of(true, listOf("a", "b")).sameOutcome(NotificationState.of(true, listOf("b", "a"))))
        assertFalse(NotificationState.of(true, listOf("a")).sameOutcome(NotificationState.of(false, listOf("a"))))
        assertFalse(NotificationState.of(true, listOf("a")).sameOutcome(NotificationState.of(true, listOf("a", "c"))))
        assertFalse(NotificationState.of(true, listOf("a")).sameOutcome(null))
    }

    @Test
    fun `when gates delivery`() {
        assertTrue(NotifyWhen.ALWAYS.allows(passed = true, changed = false))
        assertTrue(NotifyWhen.CHANGED.allows(passed = true, changed = true))
        assertTrue(NotifyWhen.CHANGED.allows(passed = true, changed = null))
        assertFalse(NotifyWhen.CHANGED.allows(passed = false, changed = false))
        assertTrue(NotifyWhen.FAILED.allows(passed = false, changed = false))
        assertFalse(NotifyWhen.FAILED.allows(passed = true, changed = true))
        assertFalse(NotifyWhen.NEVER.allows(passed = false, changed = true))
    }
}
