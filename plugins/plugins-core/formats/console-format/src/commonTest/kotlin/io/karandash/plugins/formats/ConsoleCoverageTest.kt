package io.karandash.plugins.formats

import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.testfixtures.createTestFinding
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

class ConsoleCoverageTest {
    private val module = ConsoleFormatModule()

    private fun skipped(count: Int) = List(count) { SkippedRuleInfo("rule$it", "", SkipCategory.WHEN_CONDITION) }

    @Test
    fun `concise status line counts one skipped rule in the singular`() =
        runTest {
            val report = createTestReport(
                findings = listOf(createTestFinding(ruleId = "title", message = "t", file = "a.kt", line = 1))
            ).copy(skippedRules = skipped(1))

            val body = module.format(report, JsonObject(mapOf("style" to JsonPrimitive("concise")))).body

            assertTrue(body.contains("1 rule skipped"), body)
        }

    @Test
    fun `concise status line counts several skipped rules in the plural`() =
        runTest {
            val report = createTestReport(
                findings = listOf(createTestFinding(ruleId = "title", message = "t", file = "a.kt", line = 1))
            ).copy(skippedRules = skipped(3))

            val body = module.format(report, JsonObject(mapOf("style" to JsonPrimitive("concise")))).body

            assertTrue(body.contains("3 rules skipped"), body)
        }

    @Test
    fun `full style prints a suggested code change with its line range`() =
        runTest {
            val finding =
                Finding(
                    severity = Severity.WARNING,
                    message = "use a constant",
                    rule = "codeQuality",
                    location = Location.FileRef("src/A.kt", 4),
                    metadata =
                        mapOf(
                            WellKnownMetadataKeys.SUGGESTED_CODE to "val x = 1\nval y = 2\n",
                            WellKnownMetadataKeys.EXISTING_CODE to "val x = 0\nval y = 0\n",
                        ),
                )

            val body =
                module
                    .format(createTestReport(findings = listOf(finding)), JsonObject(mapOf("style" to JsonPrimitive("full"))))
                    .body

            assertTrue(body.contains("Suggested change (lines 4-5):"), body)
            assertTrue(body.contains("      val x = 1\n      val y = 2\n"), body)
        }

    @Test
    fun `concise style lists a risk advisory under the AI review heading`() =
        runTest {
            val advisory =
                Finding(
                    severity = Severity.INFO,
                    message = "touches the payment flow",
                    rule = "ai.risk",
                    metadata = mapOf(WellKnownMetadataKeys.IS_RISK_ADVISORY to "true"),
                )

            val config = JsonObject(mapOf("style" to JsonPrimitive("concise")))
            val body = module.format(createTestReport(findings = listOf(advisory)), config).body

            assertTrue(body.contains("AI review"), body)
            assertTrue(body.contains("touches the payment flow"), body)
        }
}
