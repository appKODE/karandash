package io.karandash.validators

import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Severity
import io.karandash.patterns.DefaultPatternRegistry
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pin: the draft `wipMarkers` key is live and covered here. */
class DraftRuleModuleTest {
    private val module = DraftRuleModule()

    private fun strings(vararg values: String) = JsonArray(values.map { JsonPrimitive(it) })

    private suspend fun validate(
        title: String,
        draft: Boolean = false,
        config: JsonElement = JsonObject(emptyMap()),
    ) = module.validate(DraftRuleModule.RULE_ID, createTestMergeRequest(title = title, draft = draft), config)

    @Test
    fun `module metadata derives the id from the rule id`() =
        runTest {
            assertEquals("io.karandash.rule.draft-prevention", module.id)
            assertEquals(setOf("draftPrevention"), module.contributedRules())
            assertNotNull(module.ruleExplanation("draftPrevention"))
            assertEquals(null, module.ruleExplanation("other"))
            assertTrue(module.defaultEnabled)
        }

    @Test
    fun `draft mr yields one error with a set-draft fix`() =
        runTest {
            val finding = validate("feat: x", draft = true).single()

            assertEquals(Severity.ERROR, finding.severity)
            assertEquals("draftPrevention", finding.rule)
            assertEquals("MR is marked as draft and cannot be merged", finding.message)
            assertEquals(listOf<FixSuggestion>(FixSuggestion.SetDraft(draft = false)), finding.fixSuggestions)
        }

    @Test
    fun `draft mr suggestion tells to mark the mr as ready`() =
        runTest {
            val finding = validate("feat: x", draft = true).single()

            assertEquals("Mark the MR as ready.", finding.suggestion)
        }

    @Test
    fun `wip title suggestion names the exact marker to remove`() =
        runTest {
            val finding = validate("feat: x [WIP]").single()

            assertEquals("MR title contains WIP indicator and cannot be merged", finding.message)
            assertEquals("Remove '[WIP]' from the MR title.", finding.suggestion)
        }

    @Test
    fun `ready mr with clean title passes`() =
        runTest {
            assertTrue(validate("feat: add login").isEmpty())
        }

    @Test
    fun `other rule id returns no findings`() =
        runTest {
            val findings = module.validate("titleFormat", createTestMergeRequest(draft = true), JsonObject(emptyMap()))
            assertTrue(findings.isEmpty())
        }

    @Test
    fun `block draft false disables the rule`() =
        runTest {
            val config = JsonObject(mapOf("blockDraft" to JsonPrimitive(false)))
            assertTrue(validate("WIP: x", draft = true, config = config).isEmpty())
        }

    @Test
    fun `wip markers replace default patterns and are escaped`() =
        runTest {
            val config = JsonObject(mapOf("wipMarkers" to strings("HOLD", "[skip]")))

            assertEquals(1, validate("HOLD the release", config = config).size)
            assertTrue(validate("HOLDING pattern", config = config).isEmpty(), "word marker keeps word boundaries")
            assertEquals(1, validate("chore [skip] ci", config = config).size)
            assertTrue(validate("chore s ci", config = config).isEmpty(), "brackets are literal, not a class")
            assertTrue(validate("WIP: x", config = config).isEmpty(), "defaults no longer apply")
        }

    @Test
    fun `explicit wip patterns win over wip markers`() =
        runTest {
            val config = JsonObject(mapOf("wipPatterns" to strings("^tmp"), "wipMarkers" to strings("HOLD")))

            assertEquals(1, validate("tmp change", config = config).size)
            assertTrue(validate("HOLD change", config = config).isEmpty())
        }

    @Test
    fun `invalid wip pattern reports an error finding even for a clean mr`() =
        runTest {
            val config = JsonObject(mapOf("wipPatterns" to strings("(unclosed")))

            val findings = validate("feat: fine", config = config)

            assertEquals(1, findings.size, findings.toString())
            assertEquals("draftPrevention", findings.single().rule)
        }

    @Test
    fun `malformed config falls back to defaults with an info finding`() =
        runTest {
            val findings = validate("WIP: x", config = JsonPrimitive("nope"))

            assertEquals(listOf(Severity.ERROR, Severity.INFO), findings.map { it.severity })
            assertTrue(findings.last().message.startsWith("Invalid config for rule 'draftPrevention'"))
        }

    @Test
    fun `null config uses defaults silently`() =
        runTest {
            assertEquals(1, validate("[WIP] x", config = JsonNull).size)
        }

    @Test
    fun `registers the wip-marker named pattern`() {
        val registry = DefaultPatternRegistry()
        module.registerPatterns(registry)

        val expanded = assertNotNull(registry.resolve("wip-marker"))
        val regex = Regex(expanded)
        listOf("WIP", "[WIP] x", "Draft: y", "DO NOT MERGE").forEach { assertTrue(regex.containsMatchIn(it), it) }
        assertFalse(regex.containsMatchIn("wiping floors"))
    }
}
