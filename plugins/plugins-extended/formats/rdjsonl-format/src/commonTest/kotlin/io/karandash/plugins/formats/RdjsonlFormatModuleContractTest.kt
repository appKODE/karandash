package io.karandash.plugins.formats

import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.sdk.ModuleContractTest
import io.karandash.testfixtures.createTestError
import io.karandash.testfixtures.createTestReport
import io.karandash.testfixtures.createTestWarning
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RdjsonlFormatModuleContractTest : ModuleContractTest<RdjsonlFormatModule>() {
    override fun createModule() = RdjsonlFormatModule()

    override fun malformedConfig() = null

    // Config reaches a format per call, so the malformed-config half of the contract is checked here too.
    override suspend fun invokePrimaryWithEmptyInput(module: RdjsonlFormatModule) {
        module.format(createTestReport(findings = emptyList()), JsonObject(emptyMap()))
        module.format(createTestReport(findings = emptyList()), JsonPrimitive("not-a-valid-config"))
    }

    @Test
    fun shutdownWithoutInitialize() = runTest { verifyShutdownWithoutInitialize() }

    @Test
    fun shutdownIsIdempotent() = runTest { verifyShutdownIsIdempotent() }

    @Test
    fun initializeWithValidConfig() = runTest { verifyInitializeWithValidConfig() }

    @Test
    fun shutdownAfterInitialize() = runTest { verifyShutdownAfterInitialize() }

    @Test
    fun formatsOneLinePerFindingWithNoEnvelope() =
        runTest {
            val module = createModule()
            module.initialize(testContext())
            val findings = listOf(
                createTestError(message = "first issue"),
                createTestWarning(message = "second issue"),
            )
            val report = reportOf(findings)

            val formatted = module.format(report, JsonObject(emptyMap()))
            val lines = formatted.body.trimEnd('\n').split("\n")

            assertEquals(2, lines.size)
            for (line in lines) {
                assertTrue(line.trimStart().startsWith("{"))
                assertTrue(!line.contains("\"diagnostics\""))
                assertTrue(!line.contains("\"source\""))
            }
        }

    @Test
    fun formatsEmptyBodyForNoFindings() =
        runTest {
            val module = createModule()
            module.initialize(testContext())
            val report = reportOf(emptyList())

            val formatted = module.format(report, JsonObject(emptyMap()))

            assertEquals("", formatted.body)
        }

    private fun reportOf(findings: List<io.karandash.models.finding.Finding>): ValidationReport {
        val summary = findings.summarize()
        return ValidationReport(
            passed = summary.errors == 0,
            findings = findings,
            summary = summary,
            groupedByLocation = emptyMap(),
            inlineEligible = emptyList(),
            summaryOnly = findings,
            complianceChecklist = emptyList(),
            explanations = emptyMap(),
            metadata = ReportMetadata(version = "test", timestamp = 0L),
        )
    }

    @Test
    fun primaryMethodWithEmptyInput() = runTest { verifyPrimaryMethodWithEmptyInput() }
}
