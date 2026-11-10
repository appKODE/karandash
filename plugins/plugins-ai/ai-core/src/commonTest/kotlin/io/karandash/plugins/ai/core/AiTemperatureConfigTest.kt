package io.karandash.plugins.ai.core

import io.karandash.api.support.mapToJsonElement
import io.karandash.logger.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ai.temperatures` keeps numbers in 0.0..2.0; anything else is dropped with a warning naming the key. */
class AiTemperatureConfigTest {
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
        temps: Map<String, Any?>,
        log: WarnLogger
    ): Map<String, Double> = AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("temperatures" to temps)), log).temperatures

    @Test
    fun `non-numeric and out-of-range temperatures are dropped with a warning naming the key`() {
        val log = WarnLogger()
        val temps = parse(mapOf("riskScoring" to "high", "prSplit" to 3.5, "codeQuality" to 0.3), log)

        assertEquals(mapOf("codeQuality" to 0.3), temps)
        assertTrue(log.warnings.any { "riskScoring" in it }, log.warnings.toString())
        assertTrue(log.warnings.any { "prSplit" in it }, log.warnings.toString())
        assertEquals(2, log.warnings.size)
    }
}
