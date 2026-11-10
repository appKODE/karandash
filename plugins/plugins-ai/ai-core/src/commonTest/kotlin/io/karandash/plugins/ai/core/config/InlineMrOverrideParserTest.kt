package io.karandash.plugins.ai.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InlineMrOverrideParserTest {
    @Test
    fun `null description returns empty list`() {
        val result = InlineMrOverrideParser.parse(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `description with no override tags returns empty list`() {
        val result = InlineMrOverrideParser.parse("This is a normal MR description with no override tags.")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `valid single override is parsed`() {
        val description = """<!-- karandash-override rule: size-limits maxAdditions: 600 reason: "generated file" -->"""
        val result = InlineMrOverrideParser.parse(description)
        assertEquals(1, result.size)
        assertEquals("size-limits", result[0].rule)
        assertEquals("600", result[0].fields["maxAdditions"])
        assertEquals("generated file", result[0].reason)
    }

    @Test
    fun `multiple overrides are all parsed`() {
        val description = """
            <!-- karandash-override rule: size-limits maxAdditions: 600 reason: "generated file" -->
            Some text in between.
            <!-- karandash-override rule: commit-messages minLength: 5 reason: "squash commit" -->
        """.trimIndent()
        val result = InlineMrOverrideParser.parse(description)
        assertEquals(2, result.size)
        assertEquals("size-limits", result[0].rule)
        assertEquals("commit-messages", result[1].rule)
    }

    @Test
    fun `malformed tag without reason is ignored`() {
        val description = """<!-- karandash-override rule: size-limits maxAdditions: 600 -->"""
        val result = InlineMrOverrideParser.parse(description)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `rule key is excluded from fields map`() {
        val description = """<!-- karandash-override rule: size-limits maxAdditions: 500 reason: "test" -->"""
        val result = InlineMrOverrideParser.parse(description)
        assertFalse(result[0].fields.containsKey("rule"))
    }

    @Test
    fun `validation passes when rule is in allowed list and within max value`() {
        val overrides = listOf(InlineMrOverride("size-limits", mapOf("maxAdditions" to "500"), "ok"))
        val allowed = listOf(AllowedOverride("size-limits", listOf("maxAdditions"), mapOf("maxAdditions" to "600")))
        val validated = InlineMrOverrideParser.validate(overrides, allowed)
        assertEquals(1, validated.size)
        assertTrue(validated[0].valid)
    }

    @Test
    fun `validation rejects override for unknown rule`() {
        val overrides = listOf(InlineMrOverride("unknown-rule", mapOf("x" to "1"), "reason"))
        val allowed = listOf(AllowedOverride("size-limits"))
        val validated = InlineMrOverrideParser.validate(overrides, allowed)
        assertFalse(validated[0].valid)
        assertEquals("rule not in allowed overrides", validated[0].rejectionReason)
    }

    @Test
    fun `max value ceiling is enforced`() {
        val overrides = listOf(InlineMrOverride("size-limits", mapOf("maxAdditions" to "800"), "big MR"))
        val allowed = listOf(AllowedOverride("size-limits", listOf("maxAdditions"), mapOf("maxAdditions" to "600")))
        val validated = InlineMrOverrideParser.validate(overrides, allowed)
        assertFalse(validated[0].valid)
        assertTrue(validated[0].rejectionReason?.contains("exceeds max") == true)
    }

    @Test
    fun `allowed fields reject an override of a field outside the list`() {
        val overrides = listOf(InlineMrOverride("size-limits", mapOf("maxFiles" to "90"), "big MR"))
        val listed = InlineMrOverrideParser.validate(overrides, listOf(AllowedOverride("size-limits", listOf("maxAdditions"))))
        assertFalse(listed[0].valid)
        assertEquals("maxFiles is not an allowed field", listed[0].rejectionReason)
        assertTrue(InlineMrOverrideParser.validate(overrides, listOf(AllowedOverride("size-limits")))[0].valid)
    }

    @Test
    fun `value at exactly the ceiling is accepted`() {
        val overrides = listOf(InlineMrOverride("size-limits", mapOf("maxAdditions" to "600"), "at limit"))
        val allowed = listOf(AllowedOverride("size-limits", listOf("maxAdditions"), mapOf("maxAdditions" to "600")))
        val validated = InlineMrOverrideParser.validate(overrides, allowed)
        assertTrue(validated[0].valid)
    }

    @Test
    fun `non-numeric field value passes ceiling check`() {
        val overrides = listOf(InlineMrOverride("title-format", mapOf("pattern" to ".*"), "flexible title"))
        val allowed = listOf(AllowedOverride("title-format", listOf("pattern"), mapOf("pattern" to "100")))
        val validated = InlineMrOverrideParser.validate(overrides, allowed)
        assertTrue(validated[0].valid)
    }

    @Test
    fun `a thread command is read from a reply mention`() {
        assertEquals(ThreadCommand.IGNORE, InlineMrOverrideParser.parseThreadCommand("@karandash ignore"))
        assertEquals(ThreadCommand.WONTFIX, InlineMrOverrideParser.parseThreadCommand("ok then\n@Karandash WontFix thanks"))
        assertEquals(ThreadCommand.RESOLVE, InlineMrOverrideParser.parseThreadCommand("@karandash resolve"))
        assertEquals(ThreadCommand.UNKNOWN, InlineMrOverrideParser.parseThreadCommand("@karandash frobnicate"))
    }

    @Test
    fun `a reply without a mention has no thread command`() {
        assertEquals(null, InlineMrOverrideParser.parseThreadCommand("ignore this please"))
        assertEquals(null, InlineMrOverrideParser.parseThreadCommand("mail me at me@karandash ignore"))
    }

    @Test
    fun `a quoted or mid-sentence mention is not a thread command`() {
        assertEquals(null, InlineMrOverrideParser.parseThreadCommand("> @karandash ignore\n\nWhy would I do that"))
        assertEquals(null, InlineMrOverrideParser.parseThreadCommand("I will not write @karandash ignore here"))
        assertEquals(ThreadCommand.IGNORE, InlineMrOverrideParser.parseThreadCommand("> quoted text\n   @karandash ignore"))
    }

    @Test
    fun `why and ask are thread commands`() {
        assertEquals(ThreadCommand.WHY, InlineMrOverrideParser.parseThreadCommand("@karandash why"))
        assertEquals(ThreadCommand.ASK, InlineMrOverrideParser.parseThreadCommand("@Karandash ask is x ever null?"))
        assertEquals(setOf(ThreadCommand.WHY, ThreadCommand.ASK), ThreadCommand.entries.filter { it.answers }.toSet())
    }

    @Test
    fun `the ask question is the text after the command`() {
        assertEquals("is x ever null?", InlineMrOverrideParser.askQuestion("@karandash ask is x ever null?"))
        assertEquals("first line\nsecond line", InlineMrOverrideParser.askQuestion("thanks\n@karandash ask first line\nsecond line"))
        assertEquals(null, InlineMrOverrideParser.askQuestion("@karandash ask"))
        assertEquals(null, InlineMrOverrideParser.askQuestion("@karandash why"))
    }

    @Test
    fun `the first command line of a reply wins`() {
        assertEquals(ThreadCommand.WHY, InlineMrOverrideParser.parseThreadCommand("@karandash why\n@karandash ignore"))
        assertEquals(ThreadCommand.IGNORE, InlineMrOverrideParser.parseThreadCommand("@karandash ignore\n@karandash why"))
    }
}
