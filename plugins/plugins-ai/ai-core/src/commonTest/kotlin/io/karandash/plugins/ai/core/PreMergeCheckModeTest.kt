package io.karandash.plugins.ai.core

import io.karandash.api.support.mapToJsonElement
import io.karandash.logger.Logger
import io.karandash.plugins.ai.core.config.PreMergeCheck
import io.karandash.plugins.ai.core.config.PreMergeCheckMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ai.checks[].mode` is `warn` or `error`; an unknown value warns naming the check and falls back to `warn`. */
class PreMergeCheckModeTest {
    private class WarnLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private fun parse(
        mode: String,
        log: WarnLogger,
    ): PreMergeCheck {
        val check = mapOf("name" to "Logs", "instruction" to "No secrets logged", "mode" to mode)
        return AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("checks" to listOf(check))), log).checks.single()
    }

    @Test
    fun `an unknown mode warns naming the check and falls back to warn`() {
        val log = WarnLogger()

        val check = parse("eror", log)

        assertEquals(PreMergeCheckMode.WARN, check.mode)
        assertEquals(false, check.blocking)
        assertEquals(listOf("ai.checks 'Logs': unknown mode 'eror', using warn (expected warn or error)"), log.warnings)
    }

    @Test
    fun `error in any case is blocking with no warning`() {
        val log = WarnLogger()

        val check = parse("ERROR", log)

        assertEquals(PreMergeCheckMode.ERROR, check.mode)
        assertTrue(check.blocking)
        assertEquals(emptyList(), log.warnings)
    }

    @Test
    fun `warn decodes silently`() {
        val log = WarnLogger()

        assertEquals(PreMergeCheckMode.WARN, parse("warn", log).mode)
        assertEquals(emptyList(), log.warnings)
    }
}
