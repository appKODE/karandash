package io.karandash.config.pipeline

import io.karandash.api.module.ConfigFormatModule
import io.karandash.logger.Logger
import io.karandash.manifest.ConfigSource
import io.karandash.sdk.config.anyToJsonElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** Keys that normalize to the same name warn instead of silently dropping one. */
class ConfigKeyCollisionWarningTest {
    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private val expected = "Config keys 'title-format', 'titleFormat' all normalize to 'titleFormat'; the last one wins"

    @Test
    fun `full config parse warns when title-format and titleFormat collide`() {
        val logger = RecordingLogger()
        val element = buildJsonObject {
            put("title-format", JsonPrimitive("a"))
            put("titleFormat", JsonPrimitive("b"))
        }

        ConfigMapParser.parseFullConfig(element as JsonElement, logger = logger)

        assertEquals(listOf(expected), logger.warnings)
    }

    @Test
    fun `chain parse warns with the source url when title-format and titleFormat collide`() =
        runTest {
            val module = object : ConfigFormatModule {
                override val formatId = "yaml"

                override suspend fun parseToJsonElement(content: String): JsonElement =
                    anyToJsonElement(linkedMapOf("title-format" to "a", "titleFormat" to "b"))
            }
            val logger = RecordingLogger()
            val parser = ConfigChainParser(ConfigFormatRegistry(listOf(module)), logger = logger)
            val entry = ChainEntry(
                source = ConfigSource("file://config.yaml"),
                depth = 0,
                mergeStrategy = null,
                content = "x",
                format = "yaml",
            )

            parser.parseAndMerge(ConfigChain(listOf(entry)))

            assertEquals(listOf("file://config.yaml: $expected"), logger.warnings)
        }
}
