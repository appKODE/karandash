package io.karandash.plugins.ai.core.config

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleComposerTest {
    private fun finding(
        rule: String,
        severity: Severity = Severity.ERROR
    ) = Finding(severity = severity, message = "msg", rule = rule)

    @Test
    fun `empty compositions returns empty list`() {
        val findings = listOf(finding("title-format"))
        val result = RuleComposer.evaluate(findings, emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty findings with compositions returns empty list`() {
        val compositions = listOf(
            CompositionRule(name = "both-failed", expression = "rule_failed(title-format)", message = "Both failed"),
        )
        val result = RuleComposer.evaluate(emptyList(), compositions)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `rule_failed matches when findings exist for rule`() {
        val findings = listOf(finding("title-format"))
        val compositions = listOf(
            CompositionRule(name = "title-failed", expression = "rule_failed(title-format)", message = "Title failed"),
        )
        val result = RuleComposer.evaluate(findings, compositions)
        assertEquals(1, result.size)
        assertEquals("composed:title-failed", result[0].rule)
        assertEquals("Title failed", result[0].message)
    }

    @Test
    fun `rule_failed returns false when no findings for rule`() {
        val findings = listOf(finding("commit-messages"))
        val compositions = listOf(
            CompositionRule(name = "title-failed", expression = "rule_failed(title-format)", message = "Title failed"),
        )
        val result = RuleComposer.evaluate(findings, compositions)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `rule_passed matches when no findings for rule`() {
        val findings = listOf(finding("commit-messages"))
        val compositions = listOf(
            CompositionRule(
                name = "title-ok-but-commits-bad",
                expression = "rule_passed(title-format)",
                message = "Title passed",
            ),
        )
        val result = RuleComposer.evaluate(findings, compositions)
        assertEquals(1, result.size)
        assertEquals("composed:title-ok-but-commits-bad", result[0].rule)
    }

    @Test
    fun `rule_passed returns false when findings exist for rule`() {
        val findings = listOf(finding("title-format"))
        val compositions = listOf(
            CompositionRule(name = "title-ok", expression = "rule_passed(title-format)", message = "Title ok"),
        )
        val result = RuleComposer.evaluate(findings, compositions)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `AND expression requires both sides true`() {
        val findings = listOf(finding("title-format"), finding("commit-messages"))
        val compositions = listOf(
            CompositionRule(
                name = "both-failed",
                expression = "rule_failed(title-format) && rule_failed(commit-messages)",
                message = "Both failed",
            ),
        )
        val result = RuleComposer.evaluate(findings, compositions)
        assertEquals(1, result.size)
    }

    @Test
    fun `AND expression fails when one side is false`() {
        val findings = listOf(finding("title-format"))
        val compositions = listOf(
            CompositionRule(
                name = "both-failed",
                expression = "rule_failed(title-format) && rule_failed(commit-messages)",
                message = "Both failed",
            ),
        )
        val result = RuleComposer.evaluate(findings, compositions)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `OR expression passes when either side is true`() {
        val findings = listOf(finding("title-format"))
        val compositions = listOf(
            CompositionRule(
                name = "either-failed",
                expression = "rule_failed(title-format) || rule_failed(commit-messages)",
                message = "Either failed",
            ),
        )
        val result = RuleComposer.evaluate(findings, compositions)
        assertEquals(1, result.size)
    }

    @Test
    fun `OR expression fails when both sides are false`() {
        val findings = listOf(finding("branch-naming"))
        val compositions = listOf(
            CompositionRule(
                name = "either-failed",
                expression = "rule_failed(title-format) || rule_failed(commit-messages)",
                message = "Either failed",
            ),
        )
        val result = RuleComposer.evaluate(findings, compositions)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `NOT inverts true expression to false`() {
        val findings = listOf(finding("title-format"))
        val compositions = listOf(
            CompositionRule(
                name = "title-not-failed",
                expression = "not(rule_failed(title-format))",
                message = "Title not failed",
            ),
        )
        val result = RuleComposer.evaluate(findings, compositions)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `NOT inverts false expression to true`() {
        val findings = emptyList<Finding>()
        val compositions = listOf(
            CompositionRule(
                name = "nothing-failed",
                expression = "not(rule_failed(title-format))",
                message = "Nothing failed",
            ),
        )
        val result = RuleComposer.evaluate(findings, compositions)
        assertEquals(1, result.size)
    }

    @Test
    fun `composed finding has composed prefix in rule ID`() {
        val findings = listOf(finding("title-format"))
        val compositions = listOf(
            CompositionRule(name = "my-rule", expression = "rule_failed(title-format)", message = "msg"),
        )
        val result = RuleComposer.evaluate(findings, compositions)
        assertEquals("composed:my-rule", result[0].rule)
    }

    @Test
    fun `composed finding severity defaults to WARNING`() {
        val findings = listOf(finding("title-format"))
        val compositions = listOf(
            CompositionRule(name = "my-rule", expression = "rule_failed(title-format)", message = "msg"),
        )
        val result = RuleComposer.evaluate(findings, compositions)
        assertEquals(Severity.WARNING, result[0].severity)
    }

    @Test
    fun `composed finding severity uses specified severity`() {
        val findings = listOf(finding("title-format"))
        val compositions = listOf(
            CompositionRule(
                name = "my-rule",
                expression = "rule_failed(title-format)",
                severity = "error",
                message = "msg",
            ),
        )
        val result = RuleComposer.evaluate(findings, compositions)
        assertEquals(Severity.ERROR, result[0].severity)
    }

    @Test
    fun `rule_failed uses partial match on rule ID`() {
        val findings = listOf(finding("rules:title-format:pattern"))
        val ruleFindings = findings.groupBy { it.rule }
        assertTrue(RuleComposer.evaluateExpression("rule_failed(title-format)", ruleFindings))
    }

    @Test
    fun `bang prefix negates an operand`() {
        val ruleFindings = listOf(finding("size-limits")).groupBy { it.rule }

        assertTrue(RuleComposer.evaluateExpression("rule_failed(size-limits) && !rule_failed(test-coverage)", ruleFindings))
        assertFalse(RuleComposer.evaluateExpression("!rule_failed(size-limits)", ruleFindings))
    }

    @Test
    fun `severity functions check the finding severity of a rule`() {
        val ruleFindings = listOf(finding("ai-risk", Severity.ERROR), finding("ai-style", Severity.INFO)).groupBy { it.rule }

        assertTrue(RuleComposer.evaluateExpression("high(ai-risk)", ruleFindings))
        assertFalse(RuleComposer.evaluateExpression("medium(ai-risk)", ruleFindings))
        assertTrue(RuleComposer.evaluateExpression("low(ai-style)", ruleFindings))
    }

    @Test
    fun `keyword_match and file_match read the merge request`() {
        val mr = createTestMergeRequest(
            title = "Refactor config loader",
            changes = listOf(FileChange(path = "config/app.yaml", changeType = ChangeType.MODIFIED)),
        )

        assertTrue(RuleComposer.evaluateExpression("keyword_match(title, '(?i)refactor')", emptyMap(), mr))
        assertFalse(RuleComposer.evaluateExpression("keyword_match(description, 'x')", emptyMap(), mr))
        assertTrue(RuleComposer.evaluateExpression("file_match('**/*.yaml')", emptyMap(), mr))
        assertFalse(RuleComposer.evaluateExpression("file_match('**/*.sql')", emptyMap()))
    }

    @Test
    fun `when is accepted as the expression key`() {
        val json = """{"name":"n","when":"rule_failed(a)","message":"m"}"""
        val rule = KarandashJson.Default.decodeFromString(CompositionRule.serializer(), json)

        assertEquals("rule_failed(a)", rule.expression)
    }
}
