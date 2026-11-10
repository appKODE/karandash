package io.karandash.plugins.ai.analyzers.codesuggestions

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeSuggestionsExtraInstructionsTest {
    @Test
    fun `extra instructions reach the system prompt and the base prompt survives`() =
        runTest {
            val service = FakeAiService("""{"suggestions":[]}""")
            CodeSuggestionsAnalyzerModule().analyze(
                AiAnalyzerContext(
                    mr = createTestMergeRequest(),
                    aiService = service,
                    budget = TokenBudget(50_000),
                    model = "m",
                    config = JsonObject(mapOf("codeSuggestionMode" to JsonPrimitive("separate"))),
                    extraInstructions = "Project rule: never log card numbers",
                ),
            )

            val system = service.requests
                .single()
                .systemPrompt
                .orEmpty()
            assertTrue(system.contains("Project rule: never log card numbers"), system)
            assertTrue(system.contains("You are a code improvement assistant"), system)
        }

    @Test
    fun `inline mode makes its own suggestion call`() =
        runTest {
            val service = FakeAiService("""{"suggestions":[]}""")
            CodeSuggestionsAnalyzerModule().analyze(
                AiAnalyzerContext(
                    mr = createTestMergeRequest(),
                    aiService = service,
                    budget = TokenBudget(50_000),
                    model = "m",
                    config = JsonObject(mapOf("codeSuggestionMode" to JsonPrimitive("inline"))),
                ),
            )

            assertEquals(1, service.requests.size)
        }
}
