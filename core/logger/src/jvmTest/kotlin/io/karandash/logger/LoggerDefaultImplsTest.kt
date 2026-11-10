package io.karandash.logger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * JAR plugins compiled without JVM default interface methods call `Logger.DefaultImpls` statics
 * instead of the interface bodies; these must keep the same behavior.
 */
class LoggerDefaultImplsTest {
    private class RecordingLogger : Logger {
        val lines = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) {
            lines += message
        }
    }

    private val defaultImpls = Class.forName("io.karandash.logger.Logger\$DefaultImpls")

    private fun invoke(
        name: String,
        vararg args: Pair<Class<*>, Any>,
    ): Any? = defaultImpls.getMethod(name, *args.map { it.first }.toTypedArray()).invoke(null, *args.map { it.second }.toTypedArray())

    @Test
    fun `DefaultImpls error with throwable appends exception type and message`() {
        val logger = RecordingLogger()
        invoke(
            "error",
            Logger::class.java to logger,
            String::class.java to "boom",
            Throwable::class.java to IllegalStateException("bad"),
        )
        assertEquals(listOf("boom: IllegalStateException: bad"), logger.lines)
    }

    @Test
    fun `DefaultImpls withContext and isDebugEnabled keep interface defaults`() {
        val logger = RecordingLogger()
        assertSame(logger, invoke("withContext", Logger::class.java to logger, String::class.java to "k", String::class.java to "v"))
        assertSame(logger, invoke("withContext", Logger::class.java to logger, arrayOf("a" to "1").let { it.javaClass to it }))
        assertEquals(false, invoke("isDebugEnabled", Logger::class.java to logger))
    }
}
