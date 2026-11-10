package io.karandash.plugins.formats

import io.karandash.sdk.ModuleContractTest
import io.karandash.testfixtures.createTestError
import io.karandash.testfixtures.createTestFinding
import io.karandash.testfixtures.createTestReport
import io.karandash.translations.polish.PolishTranslationModule
import io.karandash.translations.russian.RussianTranslationModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateFormatModuleContractTest : ModuleContractTest<TemplateFormatModule>() {
    @BeforeTest
    fun registerPolishPack() {
        PolishTranslationModule.register()
        RussianTranslationModule.register()
    }

    override fun createModule() = TemplateFormatModule()

    override fun malformedConfig() = null

    override suspend fun invokePrimaryWithEmptyInput(module: TemplateFormatModule) {
        module.format(createTestReport(findings = emptyList()), JsonObject(emptyMap()))
        module.format(createTestReport(findings = emptyList()), JsonPrimitive("not-a-valid-config"))
    }

    private val report = createTestReport(listOf(createTestError(message = "bad \"quote\" <x>", file = "src/App.kt", line = 3)))

    @Test
    fun shutdownWithoutInitialize() = runTest { verifyShutdownWithoutInitialize() }

    @Test
    fun shutdownIsIdempotent() = runTest { verifyShutdownIsIdempotent() }

    @Test
    fun initializeWithValidConfig() = runTest { verifyInitializeWithValidConfig() }

    @Test
    fun shutdownAfterInitialize() = runTest { verifyShutdownAfterInitialize() }

    @Test
    fun `a user template renders the view with the chosen escape and partials`() =
        runTest {
            val config = buildJsonObject {
                put("template", "{\"verdict\":\"{{verdict}}\",\"items\":[{{#findings}}{{>item}}{{/findings}}]}")
                put("escape", "json")
                putJsonObject("partials") { put("item", "\"{{message}}\"") }
            }
            val body = createModule().format(report, config).body

            assertEquals("{\"verdict\":\"Failed\",\"items\":[\"bad \\\"quote\\\" <x>\"]}", body)
        }

    @Test
    fun `section lambdas escape raw content`() =
        runTest {
            val config = buildJsonObject { put("template", "{{#findings}}{{#html}}{{{message}}}{{/html}}{{/findings}}") }

            assertEquals("bad &quot;quote&quot; &lt;x&gt;", createModule().format(report, config).body)
        }

    @Test
    fun `without a template or with a broken one the plain fallback is used`() =
        runTest {
            val plain = createModule().format(report, JsonObject(emptyMap())).body
            val broken = createModule().format(report, buildJsonObject { put("template", "{{#findings}}unclosed") }).body

            assertTrue(plain.startsWith("Failed"), plain)
            assertTrue("src/App.kt:3" in plain, plain)
            assertEquals(plain, broken)
        }

    @Test
    fun `language style mr and maxFindings shape the view the template sees`() =
        runTest {
            val explained = createTestReport(listOf(createTestFinding(message = "m", explanation = "because")))
            val config = buildJsonObject {
                put("template", "{{verdict}}|{{mr.ref}} {{mr.title}} {{mr.url}}|{{more}}|{{#findings}}x{{/findings}}")
                put("language", "pl")
                put("maxFindings", 0)
                putJsonObject("mr") {
                    put("ref", "!7")
                    put("title", "T")
                    put("url", "https://example.com/mr/7")
                }
            }
            assertEquals("Niezaliczone|!7 T https://example.com/mr/7|1|", createModule().format(report, config).body)
            val linked = buildJsonObject {
                put("template", "{{#findings}}{{{link}}} {{shortWhere}}{{/findings}}")
                putJsonObject("mr") {
                    put("url", "https://example.com/mr/7")
                    putJsonObject("lineLinks") { put("src/App.kt:3", "https://example.com/mr/7/diffs#a_3_3") }
                }
            }
            assertEquals("https://example.com/mr/7/diffs#a_3_3 App.kt:3", createModule().format(report, linked).body)

            fun why(style: String) =
                buildJsonObject {
                    put("template", "{{#findings}}[{{why}}]{{/findings}}")
                    put("style", style)
                }
            assertEquals("[]", createModule().format(explained, why("quiet")).body)
            assertEquals("[because]", createModule().format(explained, why("standard")).body)
        }
}
