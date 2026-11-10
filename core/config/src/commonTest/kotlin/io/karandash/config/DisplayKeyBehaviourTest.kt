package io.karandash.config

import io.karandash.duration.KarandashDuration
import kotlin.test.Test
import kotlin.test.assertEquals

/** Behaviour of `display.watcher.debounce`: the interval the watch mode debounces config changes by. */
class DisplayKeyBehaviourTest {
    @Test
    fun `display watcher debounce sets the watch interval and is capped at one minute`() {
        val display = DisplayConfig(watcher = DisplayWatcherConfig(debounce = KarandashDuration.ofSeconds(2)))
        assertEquals(2000L, display.watcher.effectiveDebounce.millis)

        val tooLong = DisplayConfig(watcher = DisplayWatcherConfig(debounce = KarandashDuration.ofSeconds(300)))
        assertEquals(60_000L, tooLong.watcher.effectiveDebounce.millis)
    }
}
