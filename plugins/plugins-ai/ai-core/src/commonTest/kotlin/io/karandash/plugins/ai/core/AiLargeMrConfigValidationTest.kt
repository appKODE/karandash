package io.karandash.plugins.ai.core

import io.karandash.api.support.mapToJsonElement
import io.karandash.logger.Logger
import io.karandash.plugins.ai.core.config.AiLargeMrConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** `ai.large-mr.max-units` and `max-parallel` under 1 warn at config load and fall back to their defaults. */
class AiLargeMrConfigValidationTest {
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
        largeMr: Map<String, Any?>,
        log: WarnLogger,
    ): AiLargeMrConfig = AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("largeMr" to largeMr)), log).largeMr

    @Test
    fun `max-units and max-parallel under 1 fall back to the defaults with a warning each`() {
        val log = WarnLogger()

        val config = parse(mapOf("maxUnits" to 0, "maxParallel" to -2, "incremental" to true), log)

        assertEquals(AiLargeMrConfig(incremental = true), config)
        assertEquals(
            listOf(
                "ai.large-mr.max-units: 0 ignored, expected at least 1; using ${AiLargeMrConfig.DEFAULT_MAX_UNITS}",
                "ai.large-mr.max-parallel: -2 ignored, expected at least 1; using ${AiLargeMrConfig.DEFAULT_MAX_PARALLEL}",
            ),
            log.warnings,
        )
    }

    @Test
    fun `valid max-units and max-parallel are kept without a warning`() {
        val log = WarnLogger()

        assertEquals(AiLargeMrConfig(maxUnits = 1, maxParallel = 8), parse(mapOf("maxUnits" to 1, "maxParallel" to 8), log))
        assertEquals(emptyList(), log.warnings)
    }
}
