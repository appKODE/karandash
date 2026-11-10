package io.karandash.logger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LoggerDefaultsTest {
    private class RecordingLogger(
        private val prefix: String = "",
        val lines: MutableList<String> = mutableListOf(),
    ) : Logger {
        override fun debug(message: String) {
            lines += "D $prefix$message"
        }

        override fun info(message: String) {
            lines += "I $prefix$message"
        }

        override fun warn(message: String) {
            lines += "W $prefix$message"
        }

        override fun error(message: String) {
            lines += "E $prefix$message"
        }

        override fun withContext(
            key: String,
            value: String,
        ): Logger = RecordingLogger("$prefix[$key=$value] ", lines)
    }

    private object PlainLogger : Logger {
        val lines = mutableListOf<String>()

        override fun debug(message: String) {
            lines += message
        }

        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) {
            lines += message
        }
    }

    @Test
    fun `error with throwable appends exception type and message`() {
        val logger = RecordingLogger()
        logger.error("boom", IllegalStateException("bad state"))
        assertEquals(listOf("E boom: IllegalStateException: bad state"), logger.lines)
    }

    @Test
    fun `default isDebugEnabled is false and lazy debug builds nothing`() {
        PlainLogger.lines.clear()
        assertFalse(PlainLogger.isDebugEnabled)
        var built = false
        PlainLogger.debug {
            built = true
            "built"
        }
        assertFalse(built)
        assertEquals(emptyList(), PlainLogger.lines)
    }

    @Test
    fun `default withContext returns the same logger`() {
        assertSame(PlainLogger, PlainLogger.withContext("rule", "x"))
        assertSame(PlainLogger, PlainLogger.withContext("a" to "1", "b" to "2"))
    }

    @Test
    fun `vararg withContext accumulates every pair in order`() {
        val logger = RecordingLogger()
        logger.withContext("rule" to "title", "mr" to "7").warn("long")
        assertEquals(listOf("W [rule=title] [mr=7] long"), logger.lines)
    }

    @Test
    fun `NoOpLogger discards everything and never builds lazy debug messages`() {
        assertFalse(NoOpLogger.isDebugEnabled)
        NoOpLogger.debug("d")
        NoOpLogger.info("i")
        NoOpLogger.warn("w")
        NoOpLogger.error("e")
        NoOpLogger.error("e", IllegalArgumentException("x"))
        var built = false
        NoOpLogger.debug {
            built = true
            "never"
        }
        assertFalse(built)
        assertSame(NoOpLogger, NoOpLogger.withContext("k", "v"))
    }
}
