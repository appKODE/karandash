package io.karandash.plugins.ai.core.reporters

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindingTransformerTest {
    private fun finding(
        rule: String = "my-rule",
        severity: Severity = Severity.WARNING,
        message: String = "some message",
        explanation: String? = null,
        suggestion: String? = null,
        location: Location? = null,
        metadata: Map<String, String> = emptyMap(),
    ) = Finding(
        severity = severity,
        message = message,
        rule = rule,
        explanation = explanation,
        suggestion = suggestion,
        location = location,
        metadata = metadata,
    )

    @Test
    fun ruleFieldReplaced() {
        val result = FindingTransformer.transform(finding(rule = "title-format"), "Rule: {{rule}}")
        assertEquals("Rule: title-format", result)
    }

    @Test
    fun severityFieldReplaced() {
        val result = FindingTransformer.transform(finding(severity = Severity.ERROR), "Sev: {{severity}}")
        assertEquals("Sev: ERROR", result)
    }

    @Test
    fun messageFieldReplaced() {
        val result = FindingTransformer.transform(finding(message = "bad title"), "Msg: {{message}}")
        assertEquals("Msg: bad title", result)
    }

    @Test
    fun explanationFieldReplacedWhenPresent() {
        val result = FindingTransformer.transform(
            finding(explanation = "why it matters"),
            "Exp: {{explanation}}",
        )
        assertEquals("Exp: why it matters", result)
    }

    @Test
    fun explanationEmptyWhenAbsent() {
        val result = FindingTransformer.transform(finding(explanation = null), "Exp: {{explanation}}")
        assertEquals("Exp: ", result)
    }

    @Test
    fun suggestionFieldReplacedWhenPresent() {
        val result = FindingTransformer.transform(
            finding(suggestion = "fix it like this"),
            "Sug: {{suggestion}}",
        )
        assertEquals("Sug: fix it like this", result)
    }

    @Test
    fun suggestionEmptyWhenAbsent() {
        val result = FindingTransformer.transform(finding(suggestion = null), "Sug: {{suggestion}}")
        assertEquals("Sug: ", result)
    }

    @Test
    fun fileAndLineReplacedFromFileRef() {
        val f = finding(location = Location.FileRef("src/Foo.kt", 42))
        val result = FindingTransformer.transform(f, "{{file}}:{{line}}")
        assertEquals("src/Foo.kt:42", result)
    }

    @Test
    fun lineEmptyWhenFileRefHasNoLine() {
        val f = finding(location = Location.FileRef("src/Bar.kt"))
        val result = FindingTransformer.transform(f, "{{file}}:{{line}}")
        assertEquals("src/Bar.kt:", result)
    }

    @Test
    fun commitReplacedFromCommitRef() {
        val f = finding(location = Location.CommitRef("deadbeef"))
        val result = FindingTransformer.transform(f, "commit:{{commit}}")
        assertEquals("commit:deadbeef", result)
    }

    @Test
    fun fileAndLineEmptyForCommitRef() {
        val f = finding(location = Location.CommitRef("abc123"))
        val result = FindingTransformer.transform(f, "{{file}}|{{line}}")
        assertEquals("|", result)
    }

    @Test
    fun allLocationFieldsEmptyWhenNoLocation() {
        val f = finding(location = null)
        val result = FindingTransformer.transform(f, "{{file}}|{{line}}|{{commit}}")
        assertEquals("||", result)
    }

    @Test
    fun metadataFieldReplaced() {
        val f = finding(metadata = mapOf("jiraTicket" to "PROJ-42"))
        val result = FindingTransformer.transform(f, "Ticket: {{meta.jiraTicket}}")
        assertEquals("Ticket: PROJ-42", result)
    }

    @Test
    fun `a missing meta key renders empty like Mustache`() {
        val f = finding(metadata = emptyMap())
        val result = FindingTransformer.transform(f, "[{{meta.missing}}]")
        assertEquals("[]", result)
    }

    @Test
    fun multipleMetadataKeysAllReplaced() {
        val f = finding(metadata = mapOf("a" to "1", "b" to "2"))
        val result = FindingTransformer.transform(f, "{{meta.a}}-{{meta.b}}")
        assertEquals("1-2", result)
    }

    @Test
    fun transformAllJoinsWithSeparator() {
        val findings = listOf(
            finding(rule = "r1"),
            finding(rule = "r2"),
        )
        val result = FindingTransformer.transformAll(findings, "{{rule}}", separator = ",")
        assertEquals("r1,r2", result)
    }

    @Test
    fun transformAllDefaultSeparatorIsNewline() {
        val findings = listOf(finding(rule = "a"), finding(rule = "b"))
        val result = FindingTransformer.transformAll(findings, "{{rule}}")
        assertTrue(result.contains("\n"))
    }

    @Test
    fun transformAllEmptyListReturnsEmptyString() {
        val result = FindingTransformer.transformAll(emptyList(), "{{rule}}")
        assertEquals("", result)
    }

    @Test
    fun `a malformed template reports the parse error instead of throwing`() {
        val result = FindingTransformer.transformAll(listOf(finding()), "{{#rule}}open")
        assertTrue(result.startsWith("Invalid finding template"), result)
    }
}
