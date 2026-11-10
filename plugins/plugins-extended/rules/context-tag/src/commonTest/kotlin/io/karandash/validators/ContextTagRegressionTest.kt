package io.karandash.validators

import io.karandash.api.context.ConditionContext
import io.karandash.conditions.ExpressionEvaluator
import io.karandash.conditions.MutablePredicateRegistry
import io.karandash.patterns.DelimiterType
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextTagRegressionTest {
    @Test
    fun `tag pattern without a capture group does not crash`() {
        val config = ContextTagConfig(allowed = listOf("ios"), tagPattern = """\bios\b""")
        val findings = ContextTagValidator(config).validate(createTestMergeRequest(title = "ios fix"))
        assertTrue(findings.isEmpty(), findings.toString())
    }

    @Test
    fun `pattern without a group still reports a missing tag`() {
        val config = ContextTagConfig(allowed = listOf("ios"), tagPattern = """\bios\b""")
        val findings = ContextTagValidator(config).validate(createTestMergeRequest(title = "android fix"))
        assertEquals(1, findings.size, findings.toString())
    }

    private fun hasTag(
        title: String,
        tag: String,
    ): Boolean {
        val registry = MutablePredicateRegistry<ConditionContext>()
        ContextTagRuleModule().registerPredicates(registry)
        val ev = ExpressionEvaluator(ConditionContext(mr = createTestMergeRequest(title = title)))
        ev.registerAll(registry)
        return ev.evaluate("has_tag($tag)")
    }

    @Test
    fun `has_tag matches tags in any supported delimiter`() {
        assertTrue(hasTag("(core) fix", "core"))
        assertTrue(hasTag("{core} fix", "core"))
        assertFalse(hasTag("core fix", "core"))
    }

    @Test
    fun `delimiter parens finds the tag in parentheses and not in brackets`() {
        val config = ContextTagConfig(allowed = listOf("core"), delimiter = DelimiterType.PARENS)
        val ok = ContextTagValidator(config).validate(createTestMergeRequest(title = "(core) fix"))
        assertTrue(ok.isEmpty(), ok.toString())
        val missing = ContextTagValidator(config).validate(createTestMergeRequest(title = "[core] fix"))
        assertEquals(1, missing.size, missing.toString())
        assertTrue("(core)" in missing.single().suggestion.orEmpty(), missing.toString())
    }

    @Test
    fun `empty allowed list disables the rule`() {
        val config = ContextTagConfig(allowed = emptyList())
        val findings = ContextTagValidator(config).validate(createTestMergeRequest(title = "no tag at all"))
        assertTrue(findings.isEmpty(), findings.toString())
    }
}
