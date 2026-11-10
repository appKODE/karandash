package io.karandash.plugins.ai.core.config

import io.karandash.api.support.mapToJsonElement
import io.karandash.plugins.ai.core.AiPluginConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class AiDebugConfigTest {
    @Test
    fun `prompt dump is off by default`() {
        val cfg = AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("provider" to "openai")))

        assertEquals(PromptDumpMode.OFF, cfg.debug.prompt)
        assertEquals(AiDebugConfig.DEFAULT_PROMPT_DIR, cfg.debug.promptDir)
    }

    @Test
    fun `debug prompt artifact and its directory are read`() {
        val raw = mapToJsonElement(mapOf("debug" to mapOf("prompt" to "artifact", "promptDir" to "out/prompts")))

        val cfg = AiPluginConfig.fromPluginConfig(raw)

        assertEquals(PromptDumpMode.ARTIFACT, cfg.debug.prompt)
        assertEquals("out/prompts", cfg.debug.promptDir)
    }
}
