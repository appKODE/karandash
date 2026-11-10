package io.karandash.plugins.formats

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** the json report declares its layout version. */
class JsonFormatReportTest {
    @Test
    fun `the report carries a schemaVersion`() =
        runTest {
            val body = JsonReportFormatModule().format(createTestReport(findings = emptyList()), JsonObject(emptyMap())).body
            assertTrue("\"schemaVersion\": 1" in body, body)
        }
}
