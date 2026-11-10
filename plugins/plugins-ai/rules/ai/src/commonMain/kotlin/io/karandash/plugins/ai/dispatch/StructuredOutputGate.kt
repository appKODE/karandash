package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.api.ai.AiTokenBudget
import io.karandash.plugins.ai.core.schema.JsonSchemaGenerator
import kotlinx.serialization.KSerializer

/**
 * `ai.structuredOutput`: while the key is on, a request goes out with the JSON schema of the result
 * the analyzer decodes (its own `responseSchema`, else one generated from `resultSerializer`), and the
 * provider constrains the reply to it (Anthropic forced tool, OpenAI `json_schema`). Off, per rule
 * (`ai.<analyzer>.structuredOutput: false`) or under semi-formal reasoning (whose reply is a
 * certificate followed by JSON), the schema is dropped and the request goes out as plain JSON mode.
 *
 * With a [budget], every completion's `totalTokens` is recorded on it here, so analyzers don't.
 */
internal object StructuredOutputGate {
    fun apply(
        service: AiService,
        structuredOutput: Boolean,
        resultSerializer: KSerializer<*>? = null,
        budget: AiTokenBudget? = null,
    ): AiService =
        if (structuredOutput && resultSerializer == null && budget == null) {
            service
        } else {
            Gated(service, structuredOutput, resultSerializer, budget)
        }

    private class Gated(
        private val delegate: AiService,
        private val structuredOutput: Boolean,
        resultSerializer: KSerializer<*>?,
        private val budget: AiTokenBudget?,
    ) : AiService by delegate {
        private val schema = resultSerializer?.let { JsonSchemaGenerator.fromDescriptor(it.descriptor) }

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            val gated = if (structuredOutput) {
                request.copy(responseSchema = request.responseSchema ?: schema)
            } else {
                request.copy(responseSchema = null)
            }
            return delegate.complete(gated, providerId).also { budget?.recordSpend(it.totalTokens) }
        }
    }
}

/** The budget an analyzer sees: the dispatcher records spend, so the analyzer's [recordSpend] is a no-op. */
internal class AnalyzerBudgetView(
    private val delegate: AiTokenBudget,
) : AiTokenBudget by delegate {
    override fun recordSpend(tokens: Int) = Unit
}
