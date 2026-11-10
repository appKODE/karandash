package io.karandash.plugins.ai.rules

import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Custom AI rules see the diff, survive one bad entry, and do not turn prose replies into findings. */
class CustomAiRuleRobustnessTest {
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

    private suspend fun run(
        service: FakeAiService,
        diff: String = "",
    ) = CustomAiRuleExecutor.execute(
        rule = CustomAiRule(id = "ai-changelog", name = "Changelog", prompt = "Check the changelog"),
        mr = createTestMergeRequest(),
        aiService = service,
        provider = unusedProvider,
        budget = TokenBudget(maxTokens = 10_000),
        model = "m",
        preparedDiff = diff,
    )

    @Test
    fun `one bad custom rule entry is skipped with a warning and the rest load`() {
        val config = Json.parseToJsonElement(
            """{"customRules":[{"id":"changelog","name":"Changelog","prompt":"p"},{"id":"broken","name":"No prompt"}]}""",
        )
        val warnings = mutableListOf<String>()

        val rules = CustomAiRule.fromPluginConfig(config) { warnings += it }

        assertEquals(listOf("ai-changelog"), rules.map { it.id })
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue("customRules[1]" in warnings.single(), warnings.single())
    }

    @Test
    fun `the custom rule prompt carries the prepared diff`() =
        runTest {
            val service = FakeAiService("""{"message":""}""")

            run(service, diff = "+fun added() = 42")

            assertTrue("+fun added() = 42" in service.requests.single().prompt, service.requests.single().prompt)
        }

    @Test
    fun `a prose reply is an INFO parse error and not a finding at rule severity`() =
        runTest {
            val finding = run(FakeAiService("Looks good to me, nothing to report")).single()

            assertEquals(Severity.INFO, finding.severity)
            assertTrue("unparseable response" in finding.message, finding.message)
            assertFalse("Looks good" in finding.message, finding.message)
        }
}
