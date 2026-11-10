package io.karandash.plugins.ai.dispatch

import io.karandash.logger.Logger
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiCostEstimator
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CostDeltaSplitTest {
    private val model = "gpt-4o"
    private val findings = listOf(
        Finding(
            severity = Severity.WARNING,
            message = "m",
            rule = "r",
            metadata = mapOf(AiFindingMetadataKeys.AI_TOKENS_USED to "1000", AiFindingMetadataKeys.AI_MODEL to model),
        ),
    )

    @Test
    fun `cost uses the provider-reported input output split`() =
        runTest {
            val provider = object : AiProvider {
                override val providerId = "fake"

                override suspend fun complete(
                    prompt: String,
                    systemPrompt: String?,
                    model: String,
                    maxTokens: Int,
                    temperature: Double,
                    jsonMode: Boolean,
                ) = AiResponse("ok", promptTokens = 900, completionTokens = 100, totalTokens = 1000, model = model)

                override suspend fun close() = Unit
            }
            val metered = UsageMeteringAiProvider(provider)
            metered.complete("p")

            val delta = computeCostDelta(findings, metered.usage(budgetTokens = 1000), model)

            assertEquals(900, delta.inputTokens)
            assertEquals(100, delta.outputTokens)
            assertEquals(AiCostEstimator.estimateCost(model, 900, 100), delta.estimatedCostUsd)
        }

    @Test
    fun `prompt cache reads and writes reach the cost line`() =
        runTest {
            val provider = object : AiProvider {
                override val providerId = "fake"

                override suspend fun complete(
                    prompt: String,
                    systemPrompt: String?,
                    model: String,
                    maxTokens: Int,
                    temperature: Double,
                    jsonMode: Boolean,
                ) = AiResponse("ok", 900, 100, 1000, model, cacheReadTokens = 600, cacheWriteTokens = 200)

                override suspend fun close() = Unit
            }
            val metered = UsageMeteringAiProvider(provider)
            metered.complete("p")
            val logger = LineLogger()

            AiFeedbackCostFilter(logger).postProcess(
                ruleId = "r",
                rawFindings = findings,
                durationMs = 1,
                usage = metered.usage(budgetTokens = 1000),
                feedbackEnabled = false,
                feedbackStore = FeedbackStore(),
                defaultModel = model,
            )

            assertEquals(
                listOf("INFO [ai] rule=r model=gpt-4o tokens=900+100 cache=read:600/write:200 cost=\$0.0025"),
                logger.lines.filter { "[ai]" in it },
            )
        }

    @Test
    fun `tokens are the sum over every call of the dispatch not the call behind a finding`() {
        val usage = DispatchUsage(budgetTokens = 3000, promptTokens = 2700, completionTokens = 300)

        val delta = computeCostDelta(findings, usage, model)

        assertEquals(3000, delta.tokensUsed)
        assertEquals(2700, delta.inputTokens)
        assertEquals(300, delta.outputTokens)
    }

    @Test
    fun `without provider usage the tokens are split evenly as before`() {
        val delta = computeCostDelta(findings, DispatchUsage(budgetTokens = 0), model)
        assertEquals(500, delta.inputTokens)
        assertEquals(AiCostEstimator.estimateCost(model, 500, 500), delta.estimatedCostUsd)
    }

    @Test
    fun `post-processing logs the rule cost at info and warns once per unpriced model`() =
        runTest {
            val logger = LineLogger()
            val processor = AiFeedbackCostFilter(logger)
            val unpriced = findings.map { it.copy(metadata = it.metadata + (AiFindingMetadataKeys.AI_MODEL to "in-house-llm")) }

            processor.process(findings)
            processor.process(unpriced)
            processor.process(unpriced)

            assertEquals(
                listOf(
                    "INFO [ai] rule=r model=gpt-4o tokens=500+500 cost=\$0.0062",
                    "INFO [ai] rule=r model=in-house-llm tokens=500+500 cost=\$0.0000",
                    "WARN [ai] no price known for model 'in-house-llm': cost reported as \$0; add it under ai.pricing",
                    "INFO [ai] rule=r model=in-house-llm tokens=500+500 cost=\$0.0000",
                ),
                logger.lines.filter { "[ai]" in it },
            )
        }

    private suspend fun AiFeedbackCostFilter.process(raw: List<Finding>) =
        postProcess(
            ruleId = "r",
            rawFindings = raw,
            durationMs = 1,
            usage = DispatchUsage(budgetTokens = 0),
            feedbackEnabled = false,
            feedbackStore = FeedbackStore(),
            defaultModel = model,
        )

    private class LineLogger : Logger {
        val lines = mutableListOf<String>()

        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            lines += "DEBUG $message"
        }

        override fun info(message: String) {
            lines += "INFO $message"
        }

        override fun warn(message: String) {
            lines += "WARN $message"
        }

        override fun error(message: String) {
            lines += "ERROR $message"
        }
    }
}
