package io.karandash.plugins.formats

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.sdk.ModuleContractTest
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlackFormatModuleContractTest : ModuleContractTest<SlackFormatModule>() {
    override fun createModule() = SlackFormatModule()

    override fun malformedConfig() = null

    // Config reaches a format per call, so the malformed-config half of the contract is checked here too.
    override suspend fun invokePrimaryWithEmptyInput(module: SlackFormatModule) {
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
    fun outputIsSlackBlocksWithStatusAndCounts() =
        runTest {
            val body = createModule().format(createTestReport(), JsonObject(mapOf("style" to JsonPrimitive("verbose")))).body
            val blocks = Json.parseToJsonElement(body).jsonObject["blocks"]?.jsonArray
            val header = blocks?.first()?.jsonObject
            assertEquals("header", header?.get("type")?.jsonPrimitive?.content)
            assertTrue(
                "FAILED" in header
                    ?.get("text")
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty(),
                body
            )
            assertTrue("*Errors:* 1 | *Warnings:* 1" in body, body)
        }

    @Test
    fun `the standard style shows the verdict the counts and the merge request link`() =
        runTest {
            val config = JsonObject(
                mapOf(
                    "mr" to buildJsonObject {
                        put("ref", "!7")
                        put("title", "Add login")
                        put("url", "https://git.example/mr/7")
                        put("lineLinks", buildJsonObject { put("src/App.kt:3", "https://git.example/mr/7/diffs#a_3_3") })
                    }
                ),
            )
            val body = createModule().format(createTestReport(), config).body
            val blocks = Json
                .parseToJsonElement(body)
                .jsonObject["blocks"]
                ?.jsonArray
                .orEmpty()
            val header = blocks
                .first()
                .jsonObject["text"]
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content
                .orEmpty()
            val context = blocks[1].toString()

            assertTrue(header.startsWith("\u274C"), header)
            assertTrue("<https://git.example/mr/7|!7 Add login>" in context, context)
            assertTrue("<https://git.example/mr/7/diffs#a_3_3|App.kt:3>" in body, body)
        }

    @Test
    fun `the standard style lists five findings then the rest as a count`() =
        runTest {
            val findings = (1..8).map { Finding(Severity.ERROR, "Message $it", "rule-$it") }
            val blocks = Json
                .parseToJsonElement(
                    createModule().format(createTestReport(findings = findings), JsonObject(emptyMap())).body,
                ).jsonObject["blocks"]
                ?.jsonArray
                .orEmpty()

            assertEquals(5, blocks.count { it.jsonObject["type"]?.jsonPrimitive?.content == "section" })
            assertTrue("+3 more" in blocks.last().toString(), blocks.last().toString())
        }

    @Test
    fun primaryMethodWithEmptyInput() = runTest { verifyPrimaryMethodWithEmptyInput() }
}
