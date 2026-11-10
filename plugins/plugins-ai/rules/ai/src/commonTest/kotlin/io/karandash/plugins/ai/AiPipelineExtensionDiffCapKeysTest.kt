package io.karandash.plugins.ai

import io.karandash.api.PluginContext
import io.karandash.logger.Logger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** the `ai.limits` diff caps are live keys; computing the rule config (twice per enriched run) warns nothing. */
class AiPipelineExtensionDiffCapKeysTest {
    @Test
    fun `a diff cap limit warns nothing when the rule config is computed twice`() =
        runTest {
            val logger = RecordingLogger()
            val extension = initialized(logger)

            repeat(2) { extension.computeRuleModuleConfig(aiBlock(limits = true)) }

            assertEquals(emptyList(), logger.warnings)
        }

    private suspend fun initialized(logger: Logger): AiPipelineExtension =
        AiPipelineExtension(buildTestRegistry()).also {
            it.initialize(
                object : PluginContext {
                    override val pluginConfig: JsonElement = JsonObject(emptyMap())
                    override val logger: Logger = logger

                    override fun env(name: String): String? = null

                    override fun log(message: String) = Unit

                    override fun warn(message: String) = Unit

                    override val hostVersion: String = "1.0.0-test"
                    override val workingDirectory: String = "/tmp/test"
                },
            )
        }

    private fun aiBlock(limits: Boolean): JsonElement =
        buildJsonObject {
            put("provider", "custom")
            if (limits) put("limits", buildJsonObject { put("maxDiffCharsTotal", 900) })
        }

    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }
}
