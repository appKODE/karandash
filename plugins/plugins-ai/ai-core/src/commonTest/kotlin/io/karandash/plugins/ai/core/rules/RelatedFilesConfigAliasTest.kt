package io.karandash.plugins.ai.core.rules

import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelatedFilesConfigAliasTest {
    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private fun parse(
        json: String,
        logger: Logger? = null,
    ) = AiRuleConfig.fromPluginConfig(KarandashJson.Default.parseToJsonElement(json), logger).contextQuality

    @Test
    fun `related files are off by default`() {
        assertFalse(AiContextQualityConfig().relatedFiles.enabled)
        assertFalse(parse("""{}""").relatedFiles.enabled)
        assertFalse(parse("""{"contextQuality":{"enabled":true}}""").relatedFiles.enabled)
    }

    @Test
    fun `related files key enables related files without a warning`() {
        val logger = RecordingLogger()
        assertTrue(parse("""{"contextQuality":{"related-files":{"enabled":true}}}""", logger).relatedFiles.enabled)
        assertTrue(parse("""{"contextQuality":{"relatedFiles":{"enabled":true}}}""", logger).relatedFiles.enabled)
        assertEquals(emptyList(), logger.warnings)
    }
}
