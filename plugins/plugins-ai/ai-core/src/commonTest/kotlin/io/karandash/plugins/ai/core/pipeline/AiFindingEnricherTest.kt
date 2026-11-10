package io.karandash.plugins.ai.core.pipeline

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiService
import io.karandash.api.support.mapToJsonElement
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.FakeAiService
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiFindingEnricherTest {
    private val testModel = "gpt-4o-mini"

    private fun mockAiService(
        responseContent: String,
        available: Boolean = true,
    ): AiService =
        object : AiService {
            override val isAvailable = available

            override suspend fun complete(
                request: AiCompletionRequest,
                providerId: String?,
            ): AiCompletionResponse =
                AiCompletionResponse(
                    content = responseContent,
                    promptTokens = 100,
                    completionTokens = 100,
                    totalTokens = 200,
                    model = testModel,
                )
        }

    private fun recordingAiService(
        responseContent: String,
        prompts: MutableList<String>,
    ): AiService =
        object : AiService {
            override val isAvailable = true

            override suspend fun complete(
                request: AiCompletionRequest,
                providerId: String?,
            ): AiCompletionResponse {
                prompts += request.prompt
                return AiCompletionResponse(
                    content = responseContent,
                    promptTokens = 100,
                    completionTokens = 100,
                    totalTokens = 200,
                    model = testModel,
                )
            }
        }

    private fun configFrom(json: String): EnricherConfig = KarandashJson.Default.decodeFromString<EnricherConfig>(json)

    private val enrichFirstTwoReply = """
        {"enrichedFindings": [
          {"index": 0, "message": "Better 0"},
          {"index": 1, "message": "Better 1"},
          {"index": 2, "message": "Better 2"}
        ]}
    """.trimIndent()

    private fun failingAiService(): AiService =
        object : AiService {
            override val isAvailable = true

            override suspend fun complete(
                request: AiCompletionRequest,
                providerId: String?,
            ): AiCompletionResponse = throw AiProviderException("API error", statusCode = 500, retryable = true)
        }

    private fun finding(
        rule: String,
        severity: Severity = Severity.WARNING,
        message: String = "Default message",
    ) = Finding(severity = severity, message = message, rule = rule)

    // --- Disabled / unavailable ---

    @Test
    fun `disabled config returns originals unchanged`() =
        runTest {
            val config = EnricherConfig(enabled = false)
            val findings = listOf(finding("title-format"))
            val enricher = AiFindingEnricher(mockAiService("unused"), config, testModel)

            val result = enricher.enrich(findings)

            assertEquals(findings, result)
        }

    @Test
    fun `enabled flag from config decides whether the same reply is applied`() =
        runTest {
            val reply = """{"enrichedFindings": [{"index": 0, "message": "Better"}]}"""
            val findings = listOf(finding("title-format", Severity.WARNING, "Original"))

            val off = AiFindingEnricher(mockAiService(reply), configFrom("""{"enabled": false}"""), testModel)
            val on = AiFindingEnricher(mockAiService(reply), configFrom("""{"enabled": true}"""), testModel)

            assertEquals(findings, off.enrich(findings))
            assertEquals("Better", on.enrich(findings).single().message)
        }

    @Test
    fun `unavailable AI service returns originals unchanged`() =
        runTest {
            val config = EnricherConfig(enabled = true)
            val findings = listOf(finding("title-format"))
            val enricher = AiFindingEnricher(mockAiService("unused", available = false), config, testModel)

            val result = enricher.enrich(findings)

            assertEquals(findings, result)
        }

    // --- Eligibility filtering ---

    @Test
    fun `skips AI-rule findings when enrichNonAiFindings is true`() =
        runTest {
            val prompts = mutableListOf<String>()
            val findings = listOf(
                finding("ai-review", Severity.ERROR, "Machine finding"),
                finding("title-format", Severity.WARNING, "Plain finding"),
            )
            val enricher = AiFindingEnricher(
                recordingAiService("""{"enrichedFindings": [{"index": 0, "message": "Better"}]}""", prompts),
                configFrom("""{"enabled": true, "enrichNonAiFindings": true}"""),
                testModel,
            )

            val result = enricher.enrich(findings)

            assertFalse(prompts.single().contains("Machine finding"))
            assertEquals("Machine finding", result[0].message)
            assertEquals("Better", result[1].message)
        }

    @Test
    fun `enrichNonAiFindings false sends AI-rule findings to the LLM`() =
        runTest {
            val prompts = mutableListOf<String>()
            val findings = listOf(
                finding("ai-review", Severity.ERROR, "Machine finding"),
                finding("title-format", Severity.WARNING, "Plain finding"),
            )
            val enricher = AiFindingEnricher(
                recordingAiService("""{"enrichedFindings": [{"index": 0, "message": "Better"}]}""", prompts),
                configFrom("""{"enabled": true, "enrichNonAiFindings": false}"""),
                testModel,
            )

            val result = enricher.enrich(findings)

            assertTrue(prompts.single().contains("Machine finding"))
            assertEquals("Better", result[0].message)
            assertEquals("Plain finding", result[1].message)
        }

    @Test
    fun `skips INFO severity findings by default`() =
        runTest {
            val responseJson = """{"enrichedFindings": []}"""
            val config = EnricherConfig(enabled = true)
            val findings = listOf(
                finding("title-format", Severity.INFO, "Info finding"),
                finding("commit-messages", Severity.WARNING, "Warning finding"),
            )
            val enricher = AiFindingEnricher(mockAiService(responseJson), config, testModel)

            val result = enricher.enrich(findings)

            // INFO is in skipSeverities; only WARNING is eligible - but response has no enrichments
            assertEquals(findings, result)
        }

    @Test
    fun `skipSeverities from config decides which severities reach the LLM`() =
        runTest {
            val findings = listOf(
                finding("title-format", Severity.INFO, "Info finding"),
                finding("commit-messages", Severity.WARNING, "Warning finding"),
            )

            val defaultPrompts = mutableListOf<String>()
            AiFindingEnricher(
                recordingAiService(enrichFirstTwoReply, defaultPrompts),
                configFrom("""{"enabled": true}"""),
                testModel,
            ).enrich(findings)

            val emptyPrompts = mutableListOf<String>()
            val result = AiFindingEnricher(
                recordingAiService(enrichFirstTwoReply, emptyPrompts),
                configFrom("""{"enabled": true, "skipSeverities": []}"""),
                testModel,
            ).enrich(findings)

            assertFalse(defaultPrompts.single().contains("Info finding"))
            assertTrue(emptyPrompts.single().contains("Info finding"))
            assertEquals("Better 0", result[0].message)
            assertEquals("Better 1", result[1].message)
        }

    @Test
    fun `empty eligible list returns originals unchanged without calling LLM`() =
        runTest {
            val config = EnricherConfig(
                enabled = true,
                skipSeverities = setOf(Severity.INFO, Severity.WARNING, Severity.ERROR),
            )
            val findings = listOf(finding("title-format", Severity.WARNING))
            val enricher = AiFindingEnricher(failingAiService(), config, testModel)

            // failingAiService would throw if called; this test verifies it is NOT called
            val result = enricher.enrich(findings)

            assertEquals(findings, result)
        }

    // --- Successful enrichment ---

    @Test
    fun `enriches message explanation and suggestion from LLM response`() =
        runTest {
            val responseJson = """
            {
              "enrichedFindings": [
                {
                  "index": 0,
                  "message": "MR title must follow [PROJ-123] prefix format",
                  "explanation": "Consistent titles make git history searchable by ticket ID",
                  "suggestion": "Prefix title with '[PROJ-123] ' matching the pattern"
                }
              ]
            }
            """.trimIndent()

            val config = EnricherConfig(enabled = true)
            val original = finding("title-format", Severity.WARNING, "Title does not match pattern")
            val enricher = AiFindingEnricher(mockAiService(responseJson), config, testModel)

            val result = enricher.enrich(listOf(original))

            assertEquals(1, result.size)
            assertEquals("MR title must follow [PROJ-123] prefix format", result[0].message)
            assertEquals("Consistent titles make git history searchable by ticket ID", result[0].explanation)
            assertEquals("Prefix title with '[PROJ-123] ' matching the pattern", result[0].suggestion)
        }

    @Test
    fun `preserves original message when LLM omits message field`() =
        runTest {
            val responseJson = """
            {
              "enrichedFindings": [
                {
                  "index": 0,
                  "explanation": "Commit messages should describe intent",
                  "suggestion": "Use imperative mood: 'Add login screen'"
                }
              ]
            }
            """.trimIndent()

            val config = EnricherConfig(enabled = true)
            val original = finding("commit-messages", Severity.WARNING, "Commit message too short")
            val enricher = AiFindingEnricher(mockAiService(responseJson), config, testModel)

            val result = enricher.enrich(listOf(original))

            assertEquals("Commit message too short", result[0].message)
            assertEquals("Commit messages should describe intent", result[0].explanation)
        }

    @Test
    fun `respects maxFindings cap`() =
        runTest {
            val prompts = mutableListOf<String>()
            val findings = listOf(
                finding("title-format", Severity.WARNING, "F1"),
                finding("commit-messages", Severity.WARNING, "F2"),
                finding("labels", Severity.WARNING, "F3"),
            )
            val enricher = AiFindingEnricher(
                recordingAiService(enrichFirstTwoReply, prompts),
                configFrom("""{"enabled": true, "maxFindings": 2}"""),
                testModel,
            )

            val result = enricher.enrich(findings)

            assertFalse(prompts.single().contains("F3"))
            assertEquals(listOf("Better 0", "Better 1", "F3"), result.map { it.message })
        }

    // --- LLM failure ---

    @Test
    fun `LLM exception returns originals unchanged`() =
        runTest {
            val config = EnricherConfig(enabled = true)
            val findings = listOf(finding("title-format", Severity.WARNING, "Original"))
            val enricher = AiFindingEnricher(failingAiService(), config, testModel)

            val result = enricher.enrich(findings)

            assertEquals(findings, result)
        }

    // --- parseResponse unit tests ---

    @Test
    fun `parseResponse extracts enrichments from valid JSON`() {
        val json = """
        {
          "enrichedFindings": [
            {
              "index": 1,
              "message": "Better message",
              "explanation": "Why it matters",
              "suggestion": "How to fix"
            }
          ]
        }
        """.trimIndent()

        val enricher = AiFindingEnricher(mockAiService("unused"), EnricherConfig(), testModel)
        val result = enricher.parseResponse(json)

        assertEquals(1, result.size)
        assertEquals(1, result[0].index)
        assertEquals("Better message", result[0].message)
        assertEquals("Why it matters", result[0].explanation)
        assertEquals("How to fix", result[0].suggestion)
    }

    @Test
    fun `parseResponse returns empty list for invalid JSON`() {
        val enricher = AiFindingEnricher(mockAiService("unused"), EnricherConfig(), testModel)
        val result = enricher.parseResponse("not JSON at all")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseResponse extracts JSON from markdown code fence`() {
        val response = """
        Here is the enrichment:
        ```json
        {
          "enrichedFindings": [
            {
              "index": 0,
              "message": "Fenced message"
            }
          ]
        }
        ```
        """.trimIndent()

        val enricher = AiFindingEnricher(mockAiService("unused"), EnricherConfig(), testModel)
        val result = enricher.parseResponse(response)

        assertEquals(1, result.size)
        assertEquals("Fenced message", result[0].message)
        assertNull(result[0].explanation)
    }

    @Test
    fun `parseResponse returns empty list when enrichedFindings array is empty`() {
        val enricher = AiFindingEnricher(mockAiService("unused"), EnricherConfig(), testModel)
        val result = enricher.parseResponse("""{"enrichedFindings": []}""")

        assertTrue(result.isEmpty())
    }

    // --- EnricherConfig deserialization ---

    @Test
    fun `EnricherConfig deserialization parses camelCase keys`() {
        val raw = mapOf<String, Any?>(
            "enabled" to true,
            "enrichNonAiFindings" to false,
            "skipSeverities" to listOf("warning", "error"),
            "maxFindings" to 10,
        )
        val config = KarandashJson.Default.decodeFromJsonElement<EnricherConfig>(mapToJsonElement(raw))

        assertEquals(true, config.enabled)
        assertEquals(false, config.enrichNonAiFindings)
        assertEquals(setOf(Severity.WARNING, Severity.ERROR), config.skipSeverities)
        assertEquals(10, config.maxFindings)
    }

    @Test
    fun `EnricherConfig deserialization returns defaults for empty input`() {
        val config = KarandashJson.Default.decodeFromJsonElement<EnricherConfig>(mapToJsonElement(emptyMap<String, Any?>()))
        assertEquals(EnricherConfig(), config)
    }

    @Test
    fun `rewritten texts are asked in the response language`() =
        runTest {
            val service = FakeAiService("""{"enrichedFindings": []}""")

            AiFindingEnricher(service, EnricherConfig(enabled = true), testModel, language = "ru").enrich(listOf(finding("title-format")))

            assertTrue(
                "in Russian (ru)" in service.requests
                    .single()
                    .systemPrompt
                    .orEmpty()
            )
        }
}
