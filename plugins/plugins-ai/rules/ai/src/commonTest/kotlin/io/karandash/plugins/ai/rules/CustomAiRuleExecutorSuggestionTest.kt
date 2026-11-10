package io.karandash.plugins.ai.rules

import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomAiRuleExecutorSuggestionTest {
    private val unusedProvider = object : AiProvider {
        override val providerId = "unused"

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse = error("the AI service path is used")

        override suspend fun close() = Unit
    }

    private suspend fun run(service: FakeAiService) =
        CustomAiRuleExecutor.execute(
            rule = CustomAiRule(id = "changelog", name = "Changelog", prompt = "Check the changelog"),
            mr = createTestMergeRequest(),
            aiService = service,
            provider = unusedProvider,
            budget = TokenBudget(maxTokens = 10_000),
            model = "m",
        )

    @Test
    fun `suggestion from the response becomes the finding suggestion`() =
        runTest {
            val service =
                FakeAiService("""{"message":"CHANGELOG.md is not updated","suggestion":"Add an Unreleased entry to CHANGELOG.md"}""")

            val finding = run(service).single()

            assertEquals("CHANGELOG.md is not updated", finding.message)
            assertEquals("Add an Unreleased entry to CHANGELOG.md", finding.suggestion)
            assertTrue(
                "suggestion" in service.requests
                    .single()
                    .responseSchema
                    .toString(),
                "schema offers the field"
            )
        }

    @Test
    fun `a response without suggestion keeps the suggestion empty`() =
        runTest {
            assertNull(run(FakeAiService("""{"message":"CHANGELOG.md is not updated"}""")).single().suggestion)
        }

    @Test
    fun `the rule prompt is followed by the response language instruction`() =
        runTest {
            val service = FakeAiService("""{"message":"x"}""")

            CustomAiRuleExecutor.execute(
                rule = CustomAiRule(id = "changelog", name = "Changelog", prompt = "Check the changelog"),
                mr = createTestMergeRequest(),
                aiService = service,
                provider = unusedProvider,
                budget = TokenBudget(maxTokens = 10_000),
                model = "m",
                language = "ru",
            )

            val system = service.requests
                .single()
                .systemPrompt
                .orEmpty()
            assertTrue(system.startsWith("Check the changelog") && "in Russian (ru)" in system, system)
        }
}
