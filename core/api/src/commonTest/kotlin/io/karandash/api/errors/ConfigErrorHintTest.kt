package io.karandash.api.errors

import io.karandash.api.exception.ConfigParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Config errors carry an optional one-line fix next to the problem; the message-only constructors stay. */
class ConfigErrorHintTest {
    @Test
    fun `old message only constructors keep a null hint`() {
        val parse = ConfigParseException("bad config")
        assertEquals("bad config", parse.message)
        assertNull(parse.hint)
        val cause = IllegalStateException("io")
        val config = ConfigException("missing", cause)
        assertSame(cause, config.cause)
        assertNull(config.hint)
        assertNull(ConfigException("missing").hint)
    }

    @Test
    fun `hint is kept apart from the message`() {
        val parse = ConfigParseException("bad config", hint = "Rename it.")
        assertEquals("bad config", parse.message)
        assertEquals("Rename it.", parse.hint)
        assertEquals("Create it.", ConfigException("missing", cause = null, hint = "Create it.").hint)
    }
}
