package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.api.platform.MergeRequestField
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.TokenBudget
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StructuredOutputGateUnitTest {
    @Serializable
    private data class Verdict(
        val score: Double,
        val reason: String,
    )

    private class Recording : AiService {
        val requests = mutableListOf<AiCompletionRequest>()
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            requests += request
            return AiCompletionResponse("{}", 10, 15, 25, request.model)
        }
    }

    private val request = AiCompletionRequest(prompt = "p", model = "m")

    @Test
    fun `on attaches the analyzer result schema`() =
        runTest {
            val service = Recording()
            StructuredOutputGate.apply(service, true, Verdict.serializer()).complete(request, null)

            val schema = assertNotNull(service.requests.single().responseSchema)
            assertEquals(
                setOf("score", "reason"),
                schema.jsonObject
                    .getValue("properties")
                    .jsonObject.keys
            )
        }

    @Test
    fun `on keeps a schema the analyzer set itself`() =
        runTest {
            val service = Recording()
            val own = JsonObject(mapOf("type" to JsonPrimitive("object")))
            StructuredOutputGate.apply(service, true, Verdict.serializer()).complete(request.copy(responseSchema = own), null)

            assertEquals(own, service.requests.single().responseSchema)
        }

    @Test
    fun `off strips every schema`() =
        runTest {
            val service = Recording()
            val own = JsonObject(mapOf("type" to JsonPrimitive("object")))
            StructuredOutputGate.apply(service, false, Verdict.serializer()).complete(request.copy(responseSchema = own), null)

            assertNull(service.requests.single().responseSchema)
        }

    @Test
    fun `spend is recorded once while the analyzer view ignores its own recordSpend`() =
        runTest {
            val budget = TokenBudget(1_000)
            StructuredOutputGate.apply(Recording(), true, budget = budget).complete(request, null)
            AnalyzerBudgetView(budget).recordSpend(25)

            assertEquals(25, budget.totalConsumed())
        }

    @Test
    fun `nothing to gate returns the service unchanged`() {
        val service = Recording()
        assertSame(service, StructuredOutputGate.apply(service, true))
    }

    @Test
    fun `per analyzer structuredOutput parses from the ai block`() {
        val block = JsonObject(
            mapOf(
                "structuredOutput" to JsonPrimitive(true),
                "risk-scoring" to JsonObject(mapOf("structuredOutput" to JsonPrimitive(false))),
            ),
        )
        val config = AiPluginConfig.fromPluginConfig(AiPluginConfig.topLevelAiBlockToPluginConfigElement(block))

        assertEquals(true, config.structuredOutput)
        assertEquals(mapOf("risk-scoring" to false), config.structuredOutputs)
    }

    @Test
    fun `unavailable fields note names each field and is absent when none`() {
        assertNull(UnavailableFieldsNote.block(emptySet()))

        val note = assertNotNull(UnavailableFieldsNote.block(setOf(MergeRequestField.CI_STATUS, MergeRequestField.APPROVALS)))
        assertTrue("CI status" in note.content && "approvals" in note.content, note.content)
        assertEquals(UnavailableFieldsNote.SOURCE, note.source)
    }
}
