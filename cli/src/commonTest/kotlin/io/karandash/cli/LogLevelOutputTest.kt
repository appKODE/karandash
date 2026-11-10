package io.karandash.cli

import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.DelegatingLogger
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.logger.debug
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** OBS 5: `--quiet` prints only errors, `isDebugEnabled` follows the level, lazy debug builds nothing when off. */
class LogLevelOutputTest {
    private fun emitAll(logger: Logger) {
        logger.debug("d")
        logger.info("i")
        logger.warn("w")
        logger.error("e")
        logger.error("boom", IllegalStateException("cause"))
    }

    @Test
    fun quietPrintsOnlyErrors() {
        val out = mutableListOf<String>()
        emitAll(CliLogger(quiet = true, verbose = true, sink = out::add))
        assertEquals(listOf("[ERROR] e", "[ERROR] boom: IllegalStateException: cause"), out.map { it.substring(it.indexOf("[ERROR]")) })
    }

    @Test
    fun verboseErrorWithThrowableCarriesStackTrace() {
        val out = mutableListOf<String>()
        CliLogger(verbose = true, sink = out::add).error("boom", IllegalStateException("cause"))
        val line = out.single()
        assertTrue("[ERROR] boom: " in line && "IllegalStateException: cause" in line, line)
        assertTrue("\n" in line, "expected a stack trace: $line")
    }

    @Test
    fun isDebugEnabledFollowsLevelAndDelegation() {
        assertTrue(CliLogger(verbose = true).isDebugEnabled)
        assertFalse(CliLogger().isDebugEnabled)
        assertFalse(CliLogger(quiet = true, verbose = true).isDebugEnabled)
        assertFalse(NoOpLogger.isDebugEnabled)

        val delegating = DelegatingLogger()
        val contextual = delegating.withContext("module", "m")
        assertFalse(contextual.isDebugEnabled)
        delegating.configure(CliLogger(verbose = true))
        assertTrue(delegating.isDebugEnabled)
        assertTrue(contextual.isDebugEnabled)
    }

    @Test
    fun lazyDebugBuildsMessageOnlyWhenEnabled() {
        val built = mutableListOf<String>()
        CliLogger(sink = {}).debug { "off".also(built::add) }
        CliLogger(verbose = true, sink = {}).debug { "on".also(built::add) }
        assertEquals(listOf("on"), built)
    }
}
