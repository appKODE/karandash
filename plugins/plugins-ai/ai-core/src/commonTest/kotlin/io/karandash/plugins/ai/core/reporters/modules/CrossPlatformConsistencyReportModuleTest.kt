package io.karandash.plugins.ai.core.reporters.modules

import io.karandash.api.ai.ReportContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.plugins.ai.statistics.RunRecord
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CrossPlatformConsistencyReportModuleTest {
    private fun record(
        platform: String?,
        totalFindings: Int,
    ) = RunRecord(
        timestamp = 1_000_000L,
        mrId = null,
        totalFindings = totalFindings,
        errorCount = 0,
        warningCount = 0,
        infoCount = 0,
        findingsByRule = emptyMap(),
        platform = platform,
    )

    @Test
    fun `history is compared per recorded platform`() =
        runTest {
            val context = ReportContext(
                findings = emptyList(),
                mr = createTestMergeRequest(),
                enrichment = EnrichmentContext.EMPTY,
                config = JsonObject(emptyMap()),
            )
            val history = listOf(record("gitlab", 1), record("github", 9), record(null, 1))

            val data = CrossPlatformConsistencyReportModule().generate(context, history).data.jsonObject

            assertEquals(3, data.getValue("platformCount").jsonPrimitive.int)
            assertFalse(data.getValue("isConsistent").jsonPrimitive.boolean)
        }
}
