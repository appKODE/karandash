package io.karandash.bootstrap

import io.karandash.sdk.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootstrapLoggersTest {
    private class Recording(
        private val tag: String = "",
        private val lines: MutableList<String> = mutableListOf(),
        override val isDebugEnabled: Boolean = true,
    ) : Logger {
        val all: List<String> get() = lines

        override fun debug(message: String) {
            lines += "debug $tag$message"
        }

        override fun info(message: String) {
            lines += "info $tag$message"
        }

        override fun warn(message: String) {
            lines += "warn $tag$message"
        }

        override fun error(message: String) {
            lines += "error $tag$message"
        }

        override fun error(
            message: String,
            throwable: Throwable,
        ) {
            lines += "error $tag$message ${throwable.message}"
        }

        override fun withContext(
            key: String,
            value: String,
        ): Logger = Recording("$tag$key=$value ", lines, isDebugEnabled)
    }

    private fun Logger.logAll() {
        debug("d")
        info("i")
        warn("w")
        error("e")
        error("t", IllegalStateException("cause"))
    }

    @Test
    fun `delegating logger forwards to the configured delegate`() {
        val logger = DelegatingLogger()
        logger.logAll()
        assertFalse(logger.isDebugEnabled)

        val target = Recording()
        logger.configure(target)
        logger.logAll()

        assertTrue(logger.isDebugEnabled)
        assertEquals(listOf("debug d", "info i", "warn w", "error e", "error t cause"), target.all)
    }

    @Test
    fun `context taken before configure follows the later delegate`() {
        val logger = DelegatingLogger()
        val scoped = logger.withContext("run", "1").withContext("rule", "r")
        assertFalse(scoped.isDebugEnabled)

        val target = Recording()
        logger.configure(target)
        scoped.logAll()

        assertTrue(scoped.isDebugEnabled)
        assertEquals(
            listOf("d", "i", "w", "e").zip(listOf("debug", "info", "warn", "error")).map { (m, level) -> "$level run=1 rule=r $m" } +
                "error run=1 rule=r t cause",
            target.all,
        )
    }

    private fun cli(
        quiet: Boolean = false,
        verbose: Boolean = false,
        prefix: String? = null,
    ): Pair<CliLogger, MutableList<String>> {
        val lines = mutableListOf<String>()
        return CliLogger(quiet, verbose, prefix) { lines += it } to lines
    }

    private val timestamp = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} """)

    private fun MutableList<String>.messages() =
        map { line ->
            assertTrue(timestamp.containsMatchIn(line), line).let {
                line.replace(timestamp, "")
            }
        }

    @Test
    fun `cli logger levels follow quiet and verbose`() {
        val (normal, normalLines) = cli()
        normal.logAll()
        assertEquals(listOf("i", "[WARN] w", "[ERROR] e", "[ERROR] t: IllegalStateException: cause"), normalLines.messages())
        assertFalse(normal.isDebugEnabled)

        val (quiet, quietLines) = cli(quiet = true, verbose = true)
        quiet.logAll()
        assertEquals(listOf("[ERROR] e", "[ERROR] t: IllegalStateException: cause"), quietLines.messages())
        assertFalse(quiet.isDebugEnabled)

        val (verbose, verboseLines) = cli(verbose = true)
        verbose.logAll()
        val messages = verboseLines.messages()
        assertTrue(verbose.isDebugEnabled)
        assertEquals(listOf("[DEBUG] d", "i", "[WARN] w", "[ERROR] e"), messages.take(4))
        assertTrue("IllegalStateException: cause" in messages[4], messages[4])
    }

    @Test
    fun `cli logger adds prefix and context fields`() {
        val (logger, lines) = cli(prefix = "mod")
        logger.info("plain")
        logger.withContext("run", "7").withContext("rule", "r").warn("scoped")

        assertEquals(listOf("[mod] plain", "[WARN] [run=7 rule=r] [mod] scoped"), lines.messages())
    }

    @Test
    fun `verbose cli logger redacts credentials`() {
        val (logger, lines) = cli(verbose = true)
        logger.debug("GET https://user:hunter2@host/x")

        assertFalse("hunter2" in lines.single(), lines.single())
    }
}
