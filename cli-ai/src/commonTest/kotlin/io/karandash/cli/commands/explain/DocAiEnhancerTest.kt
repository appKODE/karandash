package io.karandash.cli.commands.explain

import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocAiEnhancerTest {
    private fun mockProvider(responseContent: String = "AI generated content"): AiProvider =
        object : AiProvider {
            override val providerId = "mock"
            var callCount = 0
                private set

            override suspend fun complete(
                prompt: String,
                systemPrompt: String?,
                model: String,
                maxTokens: Int,
                temperature: Double,
                jsonMode: Boolean,
            ): AiResponse {
                callCount++
                return AiResponse(
                    content = responseContent,
                    promptTokens = 100,
                    completionTokens = 50,
                    totalTokens = 150,
                    model = "mock-model",
                )
            }

            override suspend fun close() = Unit
        }

    private fun failingProvider(): AiProvider =
        object : AiProvider {
            override val providerId = "failing"

            override suspend fun complete(
                prompt: String,
                systemPrompt: String?,
                model: String,
                maxTokens: Int,
                temperature: Double,
                jsonMode: Boolean,
            ): AiResponse {
                error("AI service unavailable")
            }

            override suspend fun close() = Unit
        }

    private fun emptyResult() =
        ExplainResult(
            configPath = null,
            configuredModules = emptyList(),
            availableModules = emptyList(),
            globalIssues = emptyList(),
        )

    private fun moduleReport(
        id: String,
        description: String = "Test rule",
        fields: List<FieldReport> = emptyList(),
    ) = ModuleReport(
        moduleId = id,
        moduleType = "Rule",
        description = description,
        isConfigured = true,
        isDefaultEnabled = false,
        fields = fields,
        unknownKeys = emptyList(),
        missingRequired = emptyList(),
    )

    @Test
    fun `enhance adds examples section for configured modules`() =
        runTest {
            val provider = mockProvider("| Example | Valid | Why |\n|---|---|---|\n| feat: add | Yes | Matches |")
            val enhancer = DocAiEnhancer(provider)
            val result = emptyResult().copy(
                configuredModules = listOf(moduleReport("title-format")),
            )
            val templateDoc = DocExplainRenderer.render(result)

            val enhanced = enhancer.enhance(templateDoc, result)

            assertContains(enhanced, "## Examples and Common Mistakes")
        }

    @Test
    fun `enhance generates worked example`() =
        runTest {
            val provider = mockProvider("```\nTitle: feat: add login\nBranch: feat/login\n```")
            val enhancer = DocAiEnhancer(provider)
            val result = emptyResult().copy(
                configuredModules = listOf(moduleReport("title-format")),
            )
            val templateDoc = DocExplainRenderer.render(result)

            val enhanced = enhancer.enhance(templateDoc, result)

            assertContains(enhanced, "## Worked Example")
        }

    @Test
    fun `enhance with empty modules returns template unchanged`() =
        runTest {
            val provider = mockProvider()
            val enhancer = DocAiEnhancer(provider)
            val result = emptyResult()
            val templateDoc = DocExplainRenderer.render(result)

            val enhanced = enhancer.enhance(templateDoc, result)

            assertEquals(templateDoc, enhanced)
        }

    @Test
    fun `enhance handles AI provider failure gracefully`() =
        runTest {
            val provider = failingProvider()
            val enhancer = DocAiEnhancer(provider)
            val result = emptyResult().copy(
                configuredModules = listOf(moduleReport("title-format")),
            )
            val templateDoc = DocExplainRenderer.render(result)

            val enhanced = enhancer.enhance(templateDoc, result)

            assertContains(enhanced, "Validation Rules Documentation")
            assertFalse(enhanced.contains("## Examples and Common Mistakes"))
        }

    /** `explain --ai` warns about a failed AI section instead of dropping it silently. */
    @Test
    fun `a failed AI section is reported as a warning`() =
        runTest {
            val warnings = mutableListOf<String>()
            val enhancer = DocAiEnhancer(failingProvider(), warn = { warnings += it })
            val result = emptyResult().copy(configuredModules = listOf(moduleReport("title-format")))

            enhancer.enhance(DocExplainRenderer.render(result), result)

            assertEquals(2, warnings.size, warnings.joinToString("\n"))
            assertTrue(warnings.first().contains("title-format"), warnings.first())
        }

    @Test
    fun `enhance uses low temperature for deterministic output`() =
        runTest {
            var capturedTemperature = -1.0
            val provider = object : AiProvider {
                override val providerId = "capture"

                override suspend fun complete(
                    prompt: String,
                    systemPrompt: String?,
                    model: String,
                    maxTokens: Int,
                    temperature: Double,
                    jsonMode: Boolean,
                ): AiResponse {
                    capturedTemperature = temperature
                    return AiResponse("content", 10, 10, 20, "m")
                }

                override suspend fun close() = Unit
            }
            val enhancer = DocAiEnhancer(provider)
            val result = emptyResult().copy(
                configuredModules = listOf(moduleReport("title-format")),
            )

            enhancer.enhance(DocExplainRenderer.render(result), result)

            assertEquals(0.2, capturedTemperature)
        }
}
