package io.karandash.plugins.ai.core.reporters.modules

import io.karandash.api.ai.ReportContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DigestAndComplianceReportModuleTest {
    private val findings = listOf(Finding(Severity.ERROR, "bad", "r1"), Finding(Severity.WARNING, "meh", "r2"))

    private val context = ReportContext(
        findings = findings,
        mr = createTestMergeRequest(),
        enrichment = EnrichmentContext.EMPTY,
        config = JsonObject(emptyMap()),
    )

    @Test
    fun `smart digest report summarises findings for developers without AI`() =
        runTest {
            val module = SmartDigestReportModule()

            val result = module.generate(context)
            val data = result.data.jsonObject

            assertEquals(SmartDigestReportModule.REPORT_ID, result.reportId)
            assertEquals("DEVELOPER", data.getValue("audience").jsonPrimitive.content)
            assertFalse(data.getValue("aiEnhanced").jsonPrimitive.boolean)
            assertEquals(2, data.getValue("totalFindings").jsonPrimitive.int)
            assertEquals(0.5, data.getValue("passRate").jsonPrimitive.double)
            assertEquals(mapOf("pass-rate" to 0.5), result.metrics)
            assertTrue("2 finding(s): 1 error(s), 1 warning(s)" in result.formatted, result.formatted)
            assertTrue(module.initialize(TestPluginContext()))
            assertFalse(module.defaultEnabled)
        }

    @Test
    fun `compliance evidence report carries the mr id hash and per rule checklist`() =
        runTest {
            val module = ComplianceEvidenceReportModule()

            val result = module.generate(context)
            val data = result.data.jsonObject

            assertEquals(ComplianceEvidenceReportModule.REPORT_ID, result.reportId)
            assertEquals(context.mr.id, data.getValue("mrId").jsonPrimitive.content)
            assertTrue(
                data
                    .getValue("integrityHash")
                    .jsonPrimitive.content
                    .isNotBlank()
            )
            assertEquals(2, data.getValue("checklistSize").jsonPrimitive.int)
            assertTrue("Compliance Evidence Pack" in result.formatted)
            assertFalse(module.defaultEnabled)
        }
}
