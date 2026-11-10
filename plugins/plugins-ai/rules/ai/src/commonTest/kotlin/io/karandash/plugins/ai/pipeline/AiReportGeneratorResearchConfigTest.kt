package io.karandash.plugins.ai.pipeline

import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.config.AiPipelineConfig
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** `ai.research.*` reaches the research reports; unset keeps the built-in weights. */
class AiReportGeneratorResearchConfigTest {
    private val mr = createTestMergeRequest(
        changes = listOf(FileChange("src/A.kt", ChangeType.MODIFIED, diffContent = "+if (a && b) {\n")),
    )

    private fun complexityReport(config: JsonElement?): String? =
        AiReportGenerator(AiPipelineConfig(), NoOpLogger)
            .generate(emptyList(), mr, config, "mr-1", setOf("complexity-delta"))["complexity-delta"]

    @Test
    fun `default cognitive complexity weights apply without config`() {
        // if (1 + nesting 1 * 2) + && (1)
        assertEquals("Cognitive complexity increased by 4 across 1 file(s)", complexityReport(null))
    }

    @Test
    fun `configured cognitive complexity weights drive the complexity-delta report`() {
        val config = JsonObject(
            mapOf(
                "ai" to JsonObject(
                    mapOf(
                        "research" to JsonObject(
                            mapOf("cognitiveComplexity" to JsonObject(mapOf("booleanOpWeight" to JsonPrimitive(10)))),
                        ),
                    ),
                ),
            ),
        )

        assertEquals("Cognitive complexity increased by 13 across 1 file(s)", complexityReport(config))
    }
}
