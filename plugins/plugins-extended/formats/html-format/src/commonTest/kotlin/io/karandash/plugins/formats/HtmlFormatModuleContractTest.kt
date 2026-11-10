package io.karandash.plugins.formats

import io.karandash.helpers.reporter.ReportMediaKeys
import io.karandash.sdk.ModuleContractTest
import io.karandash.testfixtures.createTestError
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlFormatModuleContractTest : ModuleContractTest<HtmlFormatModule>() {
    override fun createModule() = HtmlFormatModule()

    override fun malformedConfig() = null

    // Config reaches a format per call, so the malformed-config half of the contract is checked here too.
    override suspend fun invokePrimaryWithEmptyInput(module: HtmlFormatModule) {
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
    fun outputIsHtmlWithEscapedFindings() =
        runTest {
            val report = createTestReport(listOf(createTestError(message = "bad <script> tag", file = "src/App.kt", line = 3)))
            val body = createModule().format(report, JsonObject(emptyMap())).body
            assertTrue("<html" in body, body)
            assertTrue("Failed" in body, body)
            assertTrue("bad &lt;script&gt; tag" in body, body)
            assertFalse("<script>" in body, body)
        }

    @Test
    fun `the email subject names the verdict and the merge request and a text body comes along`() =
        runTest {
            val report = createTestReport(listOf(createTestError(message = "bad", file = "src/App.kt", line = 3)))
            val mr = buildJsonObject {
                put("ref", "!7")
                put("title", "Add login")
                put("url", "https://git.example/mr/7")
                put("lineLinks", buildJsonObject { put("src/App.kt:3", "https://git.example/mr/7/diffs#a_3_3") })
            }
            val formatted = createModule().format(report, JsonObject(mapOf("mr" to mr)))

            assertEquals("[karandash] FAILED · Add login", formatted.metadata[ReportMediaKeys.SUBJECT])
            val text = formatted.metadata[ReportMediaKeys.TEXT_BODY].orEmpty()
            assertTrue("bad" in text && "<" !in text, text)
            assertTrue(
                "<a href=\"https://git.example/mr/7/diffs#a_3_3\" title=\"src/App.kt\">App.kt:3</a>" in formatted.body,
                formatted.body
            )
        }

    @Test
    fun primaryMethodWithEmptyInput() = runTest { verifyPrimaryMethodWithEmptyInput() }
}
