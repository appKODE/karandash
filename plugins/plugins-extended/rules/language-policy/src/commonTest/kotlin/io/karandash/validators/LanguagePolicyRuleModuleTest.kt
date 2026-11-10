package io.karandash.validators

import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguagePolicyRuleModuleTest {
    private fun strings(vararg values: String) = JsonArray(values.map { JsonPrimitive(it) })

    @Test
    fun `module is opt in and reports unknown scripts and bad ranges from json config`() =
        runTest {
            val module = LanguagePolicyRuleModule()
            assertFalse(module.defaultEnabled)
            assertTrue(module.initialize(TestPluginContext()))
            val config =
                JsonObject(
                    mapOf(
                        "allowedScripts" to strings("latin", "klingon"),
                        "additionalAllowedRanges" to strings("0x0400-0x04FF", "zz"),
                        "languageName" to JsonPrimitive("English or Russian"),
                    ),
                )

            val messages = module
                .validate(
                    LanguagePolicyRuleModule.RULE_ID,
                    createTestMergeRequest(title = "Привет мир"),
                    config
                ).map { it.message }

            assertEquals(2, messages.size, messages.toString())
            assertEquals("Unknown script name(s) in 'allowedScripts': klingon.", messages[0])
            assertEquals("Invalid Unicode range(s) in 'additionalAllowedRanges': zz.", messages[1])
        }

    @Test
    fun `unknown script suggestion lists the valid names`() {
        val finding = LanguagePolicyValidator(LanguagePolicyConfig(allowedScripts = listOf("latin", "klingon")))
            .validate(createTestMergeRequest(title = "ok"))
            .single()

        assertEquals(
            "Replace klingon in 'allowedScripts' with one of: ${LanguagePolicyConfig.knownScriptNames().joinToString()}.",
            finding.suggestion,
        )
    }

    @Test
    fun `disallowed characters suggestion names the language and the characters to rewrite`() {
        val finding = LanguagePolicyValidator(LanguagePolicyConfig(checkBranchName = false))
            .validate(createTestMergeRequest(title = "fix: да"))
            .single()

        assertEquals("MR title is not in English (Cyrillic found)", finding.message)
        assertEquals("Rewrite the MR title in English, replacing 'д', 'а'.", finding.suggestion)
    }

    @Test
    fun `script names are case insensitive for both matching and the unknown script warning`() {
        val config = LanguagePolicyConfig(allowedScripts = listOf("Latin", "Cyrillic"))

        assertEquals(emptyList(), config.unknownScripts)
        assertTrue(LanguagePolicyValidator(config).validate(createTestMergeRequest(title = "Привет")).isEmpty())
    }
}
