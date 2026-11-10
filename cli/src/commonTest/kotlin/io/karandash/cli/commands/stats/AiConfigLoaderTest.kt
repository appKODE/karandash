package io.karandash.cli.commands.stats

import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.logger.Logger
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.ReplyJudgeMode
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `loadAiConfig` hands `stats` the merged `ai:` block, and falls back to `null` with a warning when the config cannot load. */
class AiConfigLoaderTest {
    private class WarnLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private class Loaded(
        val ai: kotlinx.serialization.json.JsonElement?,
        val warnings: List<String>,
    )

    private suspend fun load(
        yaml: String?,
        baseYaml: String? = null,
    ): Loaded {
        val dir = TestFileHelpers.createTempDir("ai-config-loader")
        try {
            baseYaml?.let { TestFileHelpers.writeFile(Path(dir, "base.yaml"), it) }
            val config = Path(dir, "config.yaml")
            yaml?.let { TestFileHelpers.writeFile(config, it) }
            val logger = WarnLogger()
            val bootstrap = PluginBootstrap(envReader = { null }).also {
                it.logger.configure(logger)
                it.loadPluginsFromDirs(emptyList())
            }
            try {
                return Loaded(loadAiConfig(bootstrap, config.toString()), logger.warnings)
            } finally {
                bootstrap.shutdown()
            }
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    @Test
    fun `ai block of the config is returned`() =
        runTest {
            val loaded = load("ai:\n  stats-dir: custom-stats\n")

            val ai = assertNotNull(loaded.ai) as JsonObject
            assertEquals(JsonPrimitive("custom-stats"), ai["statsDir"])
            assertEquals(emptyList(), loaded.warnings)
        }

    @Test
    fun `ai block from an extended parent is merged in`() =
        runTest {
            val loaded = load("extends: base.yaml\n", baseYaml = "ai:\n  stats-dir: from-parent\n")

            val ai = assertNotNull(loaded.ai) as JsonObject
            assertEquals(JsonPrimitive("from-parent"), ai["statsDir"])
            assertEquals(null, ai["stats-dir"])
        }

    @Test
    fun `feedback reply judge inherits from an extended parent`() =
        runTest {
            val loaded = load(
                "extends: base.yaml\nai:\n  feedback:\n    enabled: true\n",
                baseYaml = "ai:\n  feedback:\n    reply-judge: llm\n",
            )

            val config = AiPluginConfig.fromPluginConfig(AiPluginConfig.topLevelAiBlockToPluginConfigElement(assertNotNull(loaded.ai)))
            assertEquals(ReplyJudgeMode.LLM, config.feedbackReplyJudge)
            assertTrue(config.feedbackEnabled)
        }

    @Test
    fun `flat feedback reply judge in the child wins over the parent`() =
        runTest {
            val loaded = load(
                "extends: base.yaml\nai:\n  feedback-reply-judge: off\n",
                baseYaml = "ai:\n  feedback:\n    reply-judge: llm\n",
            )

            val config = AiPluginConfig.fromPluginConfig(AiPluginConfig.topLevelAiBlockToPluginConfigElement(assertNotNull(loaded.ai)))
            assertEquals(ReplyJudgeMode.OFF, config.feedbackReplyJudge)
        }

    @Test
    fun `config without an ai block gives null`() =
        runTest {
            assertNull(load("dry-run: true\n").ai)
        }

    @Test
    fun `config that fails to load gives null with a warning`() =
        runTest {
            val loaded = load("ai: [unclosed\n  - : :\n")

            assertNull(loaded.ai)
            assertTrue(
                loaded.warnings.single().startsWith("config not loaded, using the default statistics directory"),
                "${loaded.warnings}"
            )
        }

    @Test
    fun `config that fails to load warns with its how to fix hint`() =
        runTest {
            val loaded = load("policies: x\n")

            assertNull(loaded.ai)
            assertTrue("→ How to fix: Write 'policies' as a list" in loaded.warnings.single(), "${loaded.warnings}")
        }

    @Test
    fun `missing config file gives null with a warning`() =
        runTest {
            val loaded = load(yaml = null)

            assertNull(loaded.ai)
            assertEquals(1, loaded.warnings.size, "${loaded.warnings}")
        }
}
