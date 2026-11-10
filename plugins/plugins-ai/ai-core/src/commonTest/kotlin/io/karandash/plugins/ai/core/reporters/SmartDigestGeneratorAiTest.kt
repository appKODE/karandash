package io.karandash.plugins.ai.core.reporters

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.FakeAiService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmartDigestGeneratorAiTest {
    private val findings = listOf(Finding(Severity.ERROR, "x".repeat(500), "ai-security"))

    @Test
    fun `available AI service writes the digest with an audience system prompt`() =
        runTest {
            val service = FakeAiService("AI SUMMARY")

            val result = SmartDigestGenerator(service, model = "m").generate(findings, DigestAudience.SECURITY)

            val request = service.requests.single()
            assertEquals("AI SUMMARY", result.summary)
            assertTrue(result.aiEnhanced)
            assertEquals(1, result.stats?.errors)
            assertEquals("m", request.model)
            assertTrue("security analyst" in request.systemPrompt.orEmpty())
            assertTrue("for a security audience" in request.prompt)
            assertFalse("x".repeat(300) in request.prompt)
        }

    @Test
    fun `failing AI service falls back to the static summary`() =
        runTest {
            val failing = object : AiService {
                override val isAvailable = true

                override suspend fun complete(
                    request: AiCompletionRequest,
                    providerId: String?,
                ): AiCompletionResponse = error("provider down")
            }

            val result = SmartDigestGenerator(failing).generate(findings, DigestAudience.MANAGER)

            assertFalse(result.aiEnhanced)
            assertTrue(result.summary.startsWith("## Risk Assessment"))
        }

    @Test
    fun `the AI digest is asked in the response language`() =
        runTest {
            val service = FakeAiService("СВОДКА")

            SmartDigestGenerator(service, model = "m", language = "ru").generate(findings, DigestAudience.DEVELOPER)

            assertTrue(
                "in Russian (ru)" in service.requests
                    .single()
                    .systemPrompt
                    .orEmpty()
            )
        }
}
