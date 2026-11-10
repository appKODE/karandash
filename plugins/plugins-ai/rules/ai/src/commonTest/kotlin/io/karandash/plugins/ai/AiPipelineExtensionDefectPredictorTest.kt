package io.karandash.plugins.ai

import io.karandash.api.module.PipelineEnvironment
import io.karandash.plugins.ai.core.research.DefectRiskLevel
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class AiPipelineExtensionDefectPredictorTest {
    private val mr = createTestMergeRequest(description = "Small change")

    private suspend fun configured(rawConfig: JsonElement?): AiPipelineExtension =
        AiPipelineExtension(buildTestRegistry()).also {
            it.onConfigured(PipelineEnvironment(aiService = null, rawConfig = rawConfig, statsDir = ".karandash/stats"))
        }

    @Test
    fun `defect prediction uses ai defectPredictor thresholds`() =
        runTest {
            val rawConfig = buildJsonObject {
                put(
                    "ai",
                    buildJsonObject {
                        put(
                            "defectPredictor",
                            buildJsonObject { put("thresholds", buildJsonObject { put("highRisk", "1%") }) },
                        )
                    },
                )
            }

            assertEquals(DefectRiskLevel.HIGH, configured(rawConfig).predictDefect(mr).riskLevel)
        }

    @Test
    fun `defect prediction without config keeps default thresholds`() =
        runTest {
            assertEquals(DefectRiskLevel.MEDIUM, configured(null).predictDefect(mr).riskLevel)
        }
}
