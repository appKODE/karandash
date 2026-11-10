package io.karandash.plugins.formats

import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestFinding
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class JunitFailOnTest {
    private val findings =
        listOf(Severity.ERROR, Severity.WARNING, Severity.INFO).map {
            createTestFinding(ruleId = "r", severity = it, message = it.name)
        }

    private suspend fun failures(vararg config: Pair<String, Any>): Int {
        val json = JsonObject(
            config.associate { (k, v) -> k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) },
        )
        val body = JunitFormatModule().format(createTestReport(findings = findings), json).body
        return Regex("<failure ").findAll(body).count()
    }

    @Test
    fun `failOn defaults to warning and picks the lowest failing severity`() =
        runTest {
            assertEquals(2, failures())
            assertEquals(1, failures("failOn" to "error"))
            assertEquals(2, failures("failOn" to "warning"))
            assertEquals(3, failures("failOn" to "info"))
        }

    @Test
    fun `failOn threshold fails errors and warnings only under strict`() =
        runTest {
            assertEquals(1, failures("failOn" to "threshold"))
            assertEquals(2, failures("failOn" to "threshold", "strict" to true))
            assertEquals(2, failures("strict" to true))
        }
}
