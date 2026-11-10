package io.karandash.plugins.ai.core

import io.karandash.api.PipelineExtensionConfig
import io.karandash.sdk.config.deserializeConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AiPluginConfigContractTest {
    @Test
    fun `ai config is a pipeline extension config decoded by deserializeConfig`() {
        val decoded: PipelineExtensionConfig =
            deserializeConfig<AiPluginConfig>(JsonObject(mapOf("provider" to JsonPrimitive("litellm"))))
        assertEquals("litellm", (decoded as AiPluginConfig).provider)
    }
}
