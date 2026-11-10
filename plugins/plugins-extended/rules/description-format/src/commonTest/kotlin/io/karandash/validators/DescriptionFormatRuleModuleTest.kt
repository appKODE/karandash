package io.karandash.validators

import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DescriptionFormatRuleModuleTest {
    private fun section(
        name: String,
        pattern: String,
    ) = JsonObject(mapOf("name" to JsonPrimitive(name), "pattern" to JsonPrimitive(pattern), "required" to JsonPrimitive(true)))

    @Test
    fun `module checks required sections from json config`() =
        runTest {
            val module = DescriptionFormatRuleModule()
            assertTrue(module.initialize(TestPluginContext()))
            val config = JsonObject(mapOf("mrSections" to JsonArray(listOf(section("Proof", "^Proof:")))))

            assertTrue(
                module.validate(DescriptionFormatRuleModule.RULE_ID, createTestMergeRequest(description = "x\nProof: ok"), config).isEmpty()
            )
            assertEquals(
                listOf("MR description is missing the required section Proof."),
                module.validate(DescriptionFormatRuleModule.RULE_ID, createTestMergeRequest(description = "x"), config).map { it.message },
            )
        }

    @Test
    fun `suggestion falls back to the section name for glob patterns that have no examples`() {
        val validator = DescriptionFormatValidator(DescriptionFormatConfig(listOf(SectionRule("Notes", "glob:*.md", required = true))))

        assertEquals(
            listOf("Add the Notes section."),
            validator.validate(createTestMergeRequest(description = "text")).map { it.suggestion },
        )
        assertEquals(
            listOf("Add the Notes section."),
            validator.validate(createTestMergeRequest(description = null)).map { it.suggestion },
        )
    }
}
