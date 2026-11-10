package io.karandash.plugins.formats

import io.karandash.Version
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

class SarifFormatModuleContractTest : ModuleContractTest<SarifFormatModule>() {
    override fun createModule() = SarifFormatModule()

    override fun malformedConfig() = null

    // Config reaches a format per call, so the malformed-config half of the contract is checked here too.
    override suspend fun invokePrimaryWithEmptyInput(module: SarifFormatModule) {
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
    fun outputIsSarif210WithOneResultPerFinding() =
        runTest {
            val body = createModule().format(createTestReport(), JsonObject(emptyMap())).body
            val log = Json.parseToJsonElement(body).jsonObject
            assertEquals("2.1.0", log["version"]?.jsonPrimitive?.content)
            val run = log["runs"]?.jsonArray?.single()?.jsonObject
            val results = run?.get("results")?.jsonArray
            assertEquals(2, results?.size, body)
            assertEquals(
                "error",
                results
                    ?.get(0)
                    ?.jsonObject
                    ?.get("level")
                    ?.jsonPrimitive
                    ?.content
            )
            assertEquals(
                "test-rule",
                results
                    ?.get(0)
                    ?.jsonObject
                    ?.get("ruleId")
                    ?.jsonPrimitive
                    ?.content
            )
        }

    @Test
    fun sarifVersionOverridesTheToolVersion() =
        runTest {
            suspend fun toolVersion(config: JsonObject): String? {
                val body = createModule().format(createTestReport(), config).body
                val run = Json
                    .parseToJsonElement(body)
                    .jsonObject["runs"]
                    ?.jsonArray
                    ?.single()
                    ?.jsonObject
                return run
                    ?.get("tool")
                    ?.jsonObject
                    ?.get("driver")
                    ?.jsonObject
                    ?.get("version")
                    ?.jsonPrimitive
                    ?.content
            }
            assertEquals("9.9.9", toolVersion(JsonObject(mapOf("version" to JsonPrimitive("9.9.9")))))
            assertEquals(Version.VERSION, toolVersion(JsonObject(emptyMap())))
            assertEquals(Version.VERSION, toolVersion(JsonObject(mapOf("version" to JsonPrimitive(" ")))))
        }

    @Test
    fun primaryMethodWithEmptyInput() = runTest { verifyPrimaryMethodWithEmptyInput() }
}
