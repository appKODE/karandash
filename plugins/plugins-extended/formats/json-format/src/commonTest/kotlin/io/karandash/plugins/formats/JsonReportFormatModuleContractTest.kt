package io.karandash.plugins.formats

import io.karandash.sdk.ModuleContractTest
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonReportFormatModuleContractTest : ModuleContractTest<JsonReportFormatModule>() {
    override fun createModule() = JsonReportFormatModule()

    override fun malformedConfig() = null

    // Config reaches a format per call, so the malformed-config half of the contract is checked here too.
    override suspend fun invokePrimaryWithEmptyInput(module: JsonReportFormatModule) {
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
    fun outputCarriesPassedFindingsAndSummary() =
        runTest {
            val body = createModule().format(createTestReport(), JsonObject(emptyMap())).body
            val root = Json.parseToJsonElement(body).jsonObject
            assertEquals("false", root["passed"]?.jsonPrimitive?.content)
            val findings = root["findings"]?.jsonArray
            assertEquals(2, findings?.size, body)
            assertEquals(
                "Test error finding",
                findings
                    ?.get(0)
                    ?.jsonObject
                    ?.get("message")
                    ?.jsonPrimitive
                    ?.content
            )
            assertTrue(root["summary"] is JsonObject, body)
        }

    @Test
    fun primaryMethodWithEmptyInput() = runTest { verifyPrimaryMethodWithEmptyInput() }
}
