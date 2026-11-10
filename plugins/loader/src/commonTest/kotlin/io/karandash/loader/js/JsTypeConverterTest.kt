package io.karandash.loader.js

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JsTypeConverterTest {
    @Test
    fun `testMergeRequestToJson includes all required fields`() {
        val mr = MergeRequest(
            id = "123",
            iid = 456,
            title = "Test MR",
            description = "Test description",
            sourceBranch = "feature",
            targetBranch = "main",
            author = User(username = "testuser", name = "Test User", email = "test@example.com"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = listOf("bug", "urgent"),
            commits = listOf(
                Commit(
                    sha = "abc123",
                    message = "Test commit",
                    author = CommitAuthor(name = "Test User", email = "test@example.com"),
                    timestamp = "2024-01-01T00:00:00Z"
                )
            ),
            changes = listOf(
                FileChange(
                    path = "src/test.kt",
                    changeType = ChangeType.MODIFIED,
                    additions = 10,
                    deletions = 5
                )
            )
        )

        val json = JsTypeConverter.mergeRequestToJson(mr)

        assertTrue(json.contains("\"id\":\"123\""))
        assertTrue(json.contains("\"iid\":456"))
        assertTrue(json.contains("\"title\":\"Test MR\""))
        assertTrue(json.contains("\"description\":\"Test description\""))
        // Verify @SerialName fields
        assertTrue(json.contains("\"source_branch\":\"feature\""))
        assertTrue(json.contains("\"target_branch\":\"main\""))
        assertTrue(json.contains("\"testuser\""))
        assertTrue(json.contains("\"opened\""))
        assertTrue(json.contains("\"bug\""))
    }

    @Test
    fun `testFindingsFromJson parses valid findings list`() {
        val json = """
            [
                {
                    "severity": "ERROR",
                    "message": "Test error",
                    "rule": "test-rule"
                },
                {
                    "severity": "WARNING",
                    "message": "Test warning",
                    "rule": "test-rule"
                }
            ]
        """

        val findings = JsTypeConverter.findingsFromJson(json)

        assertEquals(2, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("Test error", findings[0].message)
        assertEquals("test-rule", findings[0].rule)
        assertEquals(Severity.WARNING, findings[1].severity)
        assertEquals("Test warning", findings[1].message)
    }

    @Test
    fun `testFindingsFromJson handles optional fields`() {
        val json = """
            [
                {
                    "severity": "INFO",
                    "message": "Info message",
                    "rule": "info-rule",
                    "location": {
                        "type": "file",
                        "path": "test.kt",
                        "line": 42
                    },
                    "explanation": "Detailed explanation",
                    "suggestion": "Try this fix instead",
                    "metadata": {
                        "key1": "value1",
                        "key2": "value2"
                    }
                }
            ]
        """

        val findings = JsTypeConverter.findingsFromJson(json)

        assertEquals(1, findings.size)
        val finding = findings[0]
        assertEquals(Severity.INFO, finding.severity)
        assertEquals("Info message", finding.message)
        assertEquals("info-rule", finding.rule)
        val loc = finding.location as? io.karandash.models.finding.Location.FileRef
        assertNotNull(loc)
        assertEquals("test.kt", loc.path)
        assertEquals(42, loc.line)
        assertEquals("Detailed explanation", finding.explanation)
        assertEquals("Try this fix instead", finding.suggestion)
        assertEquals(2, finding.metadata.size)
        assertEquals("value1", finding.metadata["key1"])
    }

    @Test
    fun `testEscapeForJs handles backslashes`() {
        assertEquals("test\\\\backslash", JsTypeConverter.escapeForJs("test\\backslash"))
    }

    @Test
    fun `testEscapeForJs handles single quotes`() {
        assertEquals("test\\'quote", JsTypeConverter.escapeForJs("test'quote"))
    }

    @Test
    fun `testEscapeForJs handles double quotes`() {
        assertEquals("test\\\"quote", JsTypeConverter.escapeForJs("test\"quote"))
    }

    @Test
    fun `testEscapeForJs handles newlines`() {
        assertEquals("test\\nline", JsTypeConverter.escapeForJs("test\nline"))
    }

    @Test
    fun `testEscapeForJs handles tabs`() {
        assertEquals("test\\ttab", JsTypeConverter.escapeForJs("test\ttab"))
    }

    @Test
    fun `testEscapeForJs handles carriage returns`() {
        assertEquals("test\\rcarriage", JsTypeConverter.escapeForJs("test\rcarriage"))
    }

    @Test
    fun `testEscapeForJs handles multiple special characters`() {
        val input = "test\\new\nline'quote\"double\ttab\rreturn"
        val expected = "test\\\\new\\nline\\'quote\\\"double\\ttab\\rreturn"
        assertEquals(expected, JsTypeConverter.escapeForJs(input))
    }

    @Test
    fun `testStringListFromJson parses string array`() {
        val json = """["rule1", "rule2", "rule3"]"""
        val list = JsTypeConverter.stringListFromJson(json)

        assertEquals(3, list.size)
        assertEquals("rule1", list[0])
        assertEquals("rule2", list[1])
        assertEquals("rule3", list[2])
    }

    @Test
    fun `testStringListFromJson handles empty array`() {
        val json = """[]"""
        val list = JsTypeConverter.stringListFromJson(json)
        assertTrue(list.isEmpty())
    }

    @Test
    fun `testStringListFromJson handles single element`() {
        val json = """["only-one"]"""
        val list = JsTypeConverter.stringListFromJson(json)

        assertEquals(1, list.size)
        assertEquals("only-one", list[0])
    }

    @Test
    fun `testFindingsToJson creates valid JSON`() {
        val findings = listOf(
            Finding(
                severity = Severity.ERROR,
                message = "Test error",
                rule = "test-rule"
            ),
            Finding(
                severity = Severity.WARNING,
                message = "Test warning",
                rule = "warn-rule",
                location = Location.FileRef(path = "test.kt", line = 10)
            )
        )

        val json = JsTypeConverter.findingsToJson(findings)

        assertTrue(json.contains("\"severity\":\"ERROR\""))
        assertTrue(json.contains("\"message\":\"Test error\""))
        assertTrue(json.contains("\"rule\":\"test-rule\""))
        assertTrue(json.contains("\"severity\":\"WARNING\""))
        assertTrue(json.contains("\"path\":\"test.kt\""))
        assertTrue(json.contains("\"line\":10"))
    }

    @Test
    fun `testFindingsRoundTrip preserves data`() {
        val findings = listOf(
            Finding(
                severity = Severity.ERROR,
                message = "Test",
                rule = "test-rule",
                location = Location.FileRef(path = "test.kt", line = 42),
                explanation = "Explanation",
                suggestion = "Suggested fix",
                metadata = mapOf("key" to "value")
            )
        )

        val json = JsTypeConverter.findingsToJson(findings)
        val decoded = JsTypeConverter.findingsFromJson(json)

        assertEquals(findings.size, decoded.size)
        assertEquals(findings[0].severity, decoded[0].severity)
        assertEquals(findings[0].message, decoded[0].message)
        assertEquals(findings[0].rule, decoded[0].rule)
        assertEquals(findings[0].location, decoded[0].location)
        assertEquals(findings[0].explanation, decoded[0].explanation)
        assertEquals(findings[0].suggestion, decoded[0].suggestion)
        assertEquals(findings[0].metadata, decoded[0].metadata)
    }

    @Test
    fun `testFindingsFromJson handles empty array`() {
        val json = """[]"""
        val findings = JsTypeConverter.findingsFromJson(json)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `testMergeRequestToJson handles null description`() {
        val mr = MergeRequest(
            id = "123",
            iid = 456,
            title = "Test MR",
            description = null,
            sourceBranch = "feature",
            targetBranch = "main",
            author = User(username = "testuser", name = "Test User"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList()
        )

        val json = JsTypeConverter.mergeRequestToJson(mr)

        assertTrue(json.contains("\"description\":null"))
    }

    @Test
    fun `testMergeRequestToJson includes empty collections`() {
        val mr = MergeRequest(
            id = "123",
            iid = 456,
            title = "Test MR",
            description = "desc",
            sourceBranch = "feature",
            targetBranch = "main",
            author = User(username = "testuser", name = "Test User"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList()
        )

        val json = JsTypeConverter.mergeRequestToJson(mr)

        assertTrue(json.contains("\"labels\":[]"))
        assertTrue(json.contains("\"commits\":[]"))
        assertTrue(json.contains("\"changes\":[]"))
    }
}
