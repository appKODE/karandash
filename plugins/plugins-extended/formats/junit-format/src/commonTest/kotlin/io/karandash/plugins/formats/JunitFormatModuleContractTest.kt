package io.karandash.plugins.formats

import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.sdk.ModuleContractTest
import io.karandash.testfixtures.createTestFinding
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JunitFormatModuleContractTest : ModuleContractTest<JunitFormatModule>() {
    override fun createModule() = JunitFormatModule()

    override fun malformedConfig() = null

    // Config reaches a format per call, so the malformed-config half of the contract is checked here too.
    override suspend fun invokePrimaryWithEmptyInput(module: JunitFormatModule) {
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
    fun outputIsJunitXmlWithOneSuitePerRule() =
        runTest {
            val body = createModule().format(createTestReport(), JsonObject(emptyMap())).body
            assertTrue(body.startsWith("<?xml"), body)
            assertTrue("<testsuites name=\"karandash\" tests=\"2\" failures=\"2\" errors=\"0\">" in body, body)
            assertTrue("<testsuite name=\"test-rule\" tests=\"2\"" in body, body)
            assertTrue(body.trimEnd().endsWith("</testsuites>"), body)
        }

    @Test
    fun includeAiMetadataAddsTheMetadataKeysRenderedElsewhere() =
        runTest {
            val metadata = mapOf("model" to "m-1", WellKnownMetadataKeys.RISK_SCORE to "7")
            val finding = createTestFinding().copy(confidence = 0.8f, metadata = metadata)
            val report = createTestReport(findings = listOf(finding))
            val plain = createModule().format(report, JsonObject(emptyMap())).body
            val withAi = createModule().format(report, JsonObject(mapOf("includeAiMetadata" to JsonPrimitive(true)))).body
            assertTrue("Confidence: 0.8" in plain && "model: m-1" in plain, plain)
            assertFalse("riskScore: 7" in plain, plain)
            assertTrue("model: m-1" in withAi && "riskScore: 7" in withAi, withAi)
        }

    @Test
    fun primaryMethodWithEmptyInput() = runTest { verifyPrimaryMethodWithEmptyInput() }
}
