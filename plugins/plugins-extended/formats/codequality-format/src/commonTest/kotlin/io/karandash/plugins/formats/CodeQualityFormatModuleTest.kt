package io.karandash.plugins.formats

import io.karandash.models.finding.Location
import io.karandash.sdk.ModuleContractTest
import io.karandash.testfixtures.createTestError
import io.karandash.testfixtures.createTestReport
import io.karandash.testfixtures.createTestWarning
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class CodeQualityFormatModuleTest : ModuleContractTest<CodeQualityFormatModule>() {
    override fun createModule() = CodeQualityFormatModule()

    override fun malformedConfig() = null

    override suspend fun invokePrimaryWithEmptyInput(module: CodeQualityFormatModule) {
        assertEquals("[]", module.format(createTestReport(findings = emptyList()), JsonObject(emptyMap())).body)
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
    fun `each file finding becomes one CodeClimate issue and findings without a file are left out`() =
        runTest {
            val error = createTestError(message = "Null check missing", file = "./src/App.kt", line = 3)
            val report = createTestReport(listOf(error, createTestWarning().copy(location = null)))

            val issues = Json.parseToJsonElement(createModule().format(report, JsonObject(emptyMap())).body).jsonArray

            assertEquals(1, issues.size)
            val issue = issues.single().jsonObject
            assertEquals("Null check missing", issue.string("description"))
            assertEquals(error.rule, issue.string("check_name"))
            assertEquals("major", issue.string("severity"))
            val location = checkNotNull(issue["location"]).jsonObject
            assertEquals("src/App.kt", location.string("path"))
            assertEquals("3", checkNotNull(location["lines"]).jsonObject.string("begin"))
            assertEquals(true, issue.string("fingerprint")?.isNotEmpty())
        }

    @Test
    fun `warnings map to minor and the fingerprint is stable across runs`() =
        runTest {
            val warning = createTestWarning().copy(location = Location.FileRef("b.kt", 9), fingerprint = null)
            val first = createModule().format(createTestReport(listOf(warning)), JsonObject(emptyMap())).body
            val second = createModule().format(createTestReport(listOf(warning)), JsonObject(emptyMap())).body

            assertEquals(first, second)
            assertEquals(
                "minor",
                Json
                    .parseToJsonElement(first)
                    .jsonArray
                    .single()
                    .jsonObject
                    .string("severity")
            )
        }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
