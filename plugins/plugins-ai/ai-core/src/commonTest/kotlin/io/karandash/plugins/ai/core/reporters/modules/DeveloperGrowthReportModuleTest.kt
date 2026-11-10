package io.karandash.plugins.ai.core.reporters.modules

import io.karandash.api.ai.ReportContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.models.User
import io.karandash.plugins.ai.statistics.RunRecord
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class DeveloperGrowthReportModuleTest {
    private fun record(
        author: String?,
        timestamp: Long,
        totalFindings: Int,
    ) = RunRecord(
        timestamp = timestamp,
        mrId = null,
        totalFindings = totalFindings,
        errorCount = 0,
        warningCount = totalFindings,
        infoCount = 0,
        findingsByRule = emptyMap(),
        author = author,
    )

    @Test
    fun `growth is computed from the MR author's runs only`() =
        runTest {
            val context = ReportContext(
                findings = emptyList(),
                mr = createTestMergeRequest().copy(author = User(username = "alice", name = "Alice")),
                enrichment = EnrichmentContext.EMPTY,
                config = JsonObject(emptyMap()),
            )
            // alice improves 10 -> 2; bob's and unattributed runs would make it look like a decline
            val history = listOf(
                record("alice", 1, 10),
                record("bob", 2, 0),
                record("alice", 3, 2),
                record("bob", 4, 30),
                record(null, 5, 30),
            )

            val result = DeveloperGrowthReportModule().generate(context, history)

            assertEquals(
                "IMPROVING",
                result.data.jsonObject
                    .getValue("trend")
                    .jsonPrimitive.content
            )
            assertEquals(
                80.0,
                result.data.jsonObject
                    .getValue("improvementPercent")
                    .jsonPrimitive.content
                    .toDouble()
            )
        }
}
