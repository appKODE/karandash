package io.karandash.plugins.formats

import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One behaviour test per `sarif` format key. */
class SarifKeyBehaviourTest {
    private suspend fun sarif(config: JsonObject) =
        SarifFormatModule().format(createTestReport(findings = emptyList()), config).body.replace(" ", "")

    @Test
    fun `version overrides the tool version in the output`() =
        runTest {
            val body = sarif(JsonObject(mapOf("version" to JsonPrimitive("9.8.7-test"))))

            assertTrue("\"version\":\"9.8.7-test\"" in body, body)
            assertFalse("9.8.7-test" in sarif(JsonObject(emptyMap())))
        }
}
