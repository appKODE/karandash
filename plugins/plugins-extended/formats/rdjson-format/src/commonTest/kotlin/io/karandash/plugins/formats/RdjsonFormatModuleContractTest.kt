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

class RdjsonFormatModuleContractTest : ModuleContractTest<RdjsonFormatModule>() {
    override fun createModule() = RdjsonFormatModule()

    override fun malformedConfig() = null

    // Config reaches a format per call, so the malformed-config half of the contract is checked here too.
    override suspend fun invokePrimaryWithEmptyInput(module: RdjsonFormatModule) {
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
    fun outputIsRdjsonWithOneDiagnosticPerFinding() =
        runTest {
            val body = createModule().format(createTestReport(), JsonObject(emptyMap())).body
            val root = Json.parseToJsonElement(body).jsonObject
            assertEquals(
                "karandash",
                root["source"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content
            )
            val diagnostics = root["diagnostics"]?.jsonArray
            assertEquals(2, diagnostics?.size, body)
            val first = diagnostics?.get(0)?.jsonObject
            assertEquals("ERROR", first?.get("severity")?.jsonPrimitive?.content)
            assertEquals(
                "src/App.kt",
                first
                    ?.get("location")
                    ?.jsonObject
                    ?.get("path")
                    ?.jsonPrimitive
                    ?.content
            )
        }

    @Test
    fun primaryMethodWithEmptyInput() = runTest { verifyPrimaryMethodWithEmptyInput() }
}
