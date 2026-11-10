package io.karandash.plugins.ai.core.reporters

import io.karandash.helpers.reporter.escapeHtml
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnotatedDiffHtmlBuilderTest {
    private fun mr(
        title: String = "My MR",
        sourceBranch: String = "feat/x",
        targetBranch: String = "main",
        changes: List<FileChange> = emptyList(),
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = title,
        description = null,
        sourceBranch = sourceBranch,
        targetBranch = targetBranch,
        author = User(username = "dev", name = "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList<Commit>(),
        changes = changes,
    )

    private fun fileChange(
        path: String,
        diff: String? = null
    ) = FileChange(path = path, changeType = ChangeType.MODIFIED, diffContent = diff)

    private fun finding(
        path: String,
        severity: Severity = Severity.WARNING,
        message: String = "issue",
        line: Int? = null,
    ) = Finding(
        severity = severity,
        message = message,
        rule = "test-rule",
        location = Location.FileRef(path, line),
    )

    @Test
    fun htmlContainsDoctypeAndCharset() {
        val html = AnnotatedDiffHtmlBuilder.build(mr(), emptyList())
        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("charset=\"UTF-8\""))
    }

    @Test
    fun `single quote is escaped so it cannot close an attribute`() {
        assertEquals("a&#39; onmouseover=&#39;x", "a' onmouseover='x".escapeHtml())
    }

    @Test
    fun htmlContainsMrTitle() {
        val html = AnnotatedDiffHtmlBuilder.build(mr(title = "Fix bug #42"), emptyList())
        assertTrue(html.contains("Fix bug #42"))
    }

    @Test
    fun htmlContainsBranchInfo() {
        val html = AnnotatedDiffHtmlBuilder.build(mr(sourceBranch = "feat/abc", targetBranch = "main"), emptyList())
        assertTrue(html.contains("feat/abc"))
        assertTrue(html.contains("main"))
    }

    @Test
    fun fileSectionsRendered() {
        val changes = listOf(fileChange("src/Foo.kt"), fileChange("src/Bar.kt"))
        val html = AnnotatedDiffHtmlBuilder.build(mr(changes = changes), emptyList())
        assertTrue(html.contains("src/Foo.kt"))
        assertTrue(html.contains("src/Bar.kt"))
    }

    @Test
    fun diffContentRenderedInPreBlock() {
        val changes = listOf(fileChange("src/X.kt", diff = "+ added line"))
        val html = AnnotatedDiffHtmlBuilder.build(mr(changes = changes), emptyList())
        assertTrue(html.contains("<pre"))
        assertTrue(html.contains("+ added line"))
    }

    @Test
    fun annotationHasErrorCssClass() {
        val changes = listOf(fileChange("src/A.kt"))
        val findings = listOf(finding("src/A.kt", Severity.ERROR, "error msg"))
        val html = AnnotatedDiffHtmlBuilder.build(mr(changes = changes), findings)
        assertTrue(html.contains("class=\"annotation error\""))
        assertTrue(html.contains("error msg"))
    }

    @Test
    fun annotationHasWarningCssClass() {
        val changes = listOf(fileChange("src/B.kt"))
        val findings = listOf(finding("src/B.kt", Severity.WARNING, "warn msg"))
        val html = AnnotatedDiffHtmlBuilder.build(mr(changes = changes), findings)
        assertTrue(html.contains("class=\"annotation warning\""))
    }

    @Test
    fun annotationHasInfoCssClass() {
        val changes = listOf(fileChange("src/C.kt"))
        val findings = listOf(finding("src/C.kt", Severity.INFO, "info msg"))
        val html = AnnotatedDiffHtmlBuilder.build(mr(changes = changes), findings)
        assertTrue(html.contains("class=\"annotation info\""))
    }

    @Test
    fun annotationIncludesLineNumber() {
        val changes = listOf(fileChange("src/D.kt"))
        val findings = listOf(finding("src/D.kt", Severity.WARNING, "msg", line = 42))
        val html = AnnotatedDiffHtmlBuilder.build(mr(changes = changes), findings)
        assertTrue(html.contains("L42:"))
    }

    @Test
    fun findingsWithoutFileRefNotRendered() {
        val changes = listOf(fileChange("src/E.kt"))
        val commitFinding = Finding(
            severity = Severity.ERROR,
            message = "commit issue",
            rule = "r",
            location = Location.CommitRef("abc"),
        )
        val html = AnnotatedDiffHtmlBuilder.build(mr(changes = changes), listOf(commitFinding))
        assertFalse(html.contains("commit issue"))
    }

    @Test
    fun `a single quote in a finding message is escaped`() {
        val change = fileChange("a.kt", "@@ -1 +1 @@\n+x")
        val html = AnnotatedDiffHtmlBuilder.build(mr(changes = listOf(change)), listOf(finding("a.kt", message = "it's <b>", line = 1)))
        assertTrue(html.contains("it&#39;s &lt;b&gt;"))
    }

    @Test
    fun titleIsHtmlEscaped() {
        val html = AnnotatedDiffHtmlBuilder.build(mr(title = "<b>Bold</b>"), emptyList())
        assertTrue(html.contains("&lt;b&gt;Bold&lt;/b&gt;"))
        assertFalse(html.contains("<b>Bold</b>"))
    }
}
