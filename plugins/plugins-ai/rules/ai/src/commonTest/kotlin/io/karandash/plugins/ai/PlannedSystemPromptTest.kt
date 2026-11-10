package io.karandash.plugins.ai

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.models.finding.Finding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T0 counts the system prompt the analyzer actually sends, override or not. */
class PlannedSystemPromptTest {
    @Test
    fun `an overridden prompt still counts the response format block the analyzer appends`() {
        val prompt = plannedSystemPrompt("OVERRIDE", Analyzer(responseFormatSuffix = "SCHEMA"))

        assertTrue(prompt.startsWith("OVERRIDE"), prompt)
        assertTrue("SCHEMA" in prompt, prompt)
        assertTrue("CRITICAL RESPONSE FORMAT REQUIREMENTS" in prompt, prompt)
        assertFalse("DEFAULT" in prompt, prompt)
    }

    @Test
    fun `an analyzer without a response format sends its default prompt alone`() {
        assertEquals("DEFAULT", plannedSystemPrompt(null, Analyzer(responseFormatSuffix = null)))
    }

    private class Analyzer(
        override val responseFormatSuffix: String?,
    ) : AiAnalyzerModule {
        override val analyzerId: String = "fake"
        override val id: String = "fake"
        override val name: String = "fake"
        override val version: String = "test"
        override val featureKey: String = "fake"
        override val defaultEnabled: Boolean = true
        override val defaultPrompt: String = "DEFAULT"

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> = emptyList()
    }
}
