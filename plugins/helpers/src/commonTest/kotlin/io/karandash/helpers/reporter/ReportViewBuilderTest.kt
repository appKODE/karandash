package io.karandash.helpers.reporter

import io.karandash.api.finding.BaselineComparison
import io.karandash.api.finding.ComplianceItem
import io.karandash.api.finding.ComplianceStatus
import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.report.MrView
import io.karandash.models.report.ReportStyle
import io.karandash.models.support.WellKnownMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportViewBuilderTest {
    private fun finding(
        severity: Severity,
        rule: String,
        location: Location? = null,
        suggestion: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) = Finding(
        severity = severity,
        message = "$rule says\nsecond line",
        rule = rule,
        location = location,
        suggestion = suggestion,
        metadata = metadata
    )

    private fun report(
        findings: List<Finding>,
        passed: Boolean = false,
        baseline: BaselineComparison? = null,
    ) = ValidationReport(
        passed = passed,
        findings = findings,
        summary = findings.summarize(),
        groupedByLocation = emptyMap(),
        inlineEligible = emptyList(),
        summaryOnly = findings,
        complianceChecklist = listOf(ComplianceItem("title", ComplianceStatus.PASSED)),
        explanations = emptyMap(),
        metadata = ReportMetadata(version = "1.0.0", timestamp = 0L),
        skippedRules = listOf(SkippedRuleInfo("jira", "no token")),
        baselineComparison = baseline,
    )

    private val sample =
        listOf(
            finding(Severity.INFO, "style", Location.FileRef("b.kt", 17)),
            finding(Severity.ERROR, "review", Location.FileRef("a.kt", 42), suggestion = "return a fallback"),
            finding(Severity.ERROR, "labels"),
            finding(Severity.WARNING, "ai", Location.FileRef("a.kt", 88), metadata = mapOf(WellKnownMetadataKeys.AI_MODEL to "m")),
            finding(
                Severity.INFO,
                "risk",
                metadata = mapOf(WellKnownMetadataKeys.IS_RISK_ADVISORY to "true", WellKnownMetadataKeys.RISK_SCORE to "72"),
            ),
        )

    @Test
    fun `rows are sorted by severity with MR-level findings first and advisories left out`() {
        val view = report(sample).toView()

        assertEquals(listOf("labels", "review", "ai", "style"), view.findings.map { it.rule })
        assertEquals(listOf("MR", "a.kt:42", "a.kt:88", "b.kt:17"), view.findings.map { it.where })
        assertEquals("labels says", view.findings.first().message)
        assertEquals("return a fallback", view.findings[1].fix)
        assertEquals(listOf("MR", "a.kt", "b.kt"), view.files.map { it.name })
    }

    @Test
    fun `headline counts and delta read as one line`() {
        val view = report(sample, baseline = BaselineComparison(newFindings = 2, resolvedFindings = 1)).toView()

        assertEquals("Failed · 2 errors · 1 warning · 1 info — 2 new, 1 fixed since last run", view.headline)
        assertEquals(4, view.counts.total)
        assertEquals("karandash 1.0.0", view.footer)
    }

    @Test
    fun `without a baseline there is no delta`() {
        val view = report(emptyList(), passed = true).toView()

        assertNull(view.delta)
        assertEquals("Passed", view.headline)
        assertTrue(view.passed)
    }

    @Test
    fun `details carry risk skipped rules passed rules and the fixed count`() {
        val details = report(sample, baseline = BaselineComparison(resolvedFindings = 3)).toView().details

        assertEquals(72, details.risk)
        assertEquals(listOf("title"), details.passedRules)
        assertEquals(
            listOf("Risk 72", "1 rule skipped: jira (no token)", "Passed: title", "3 fixed since last run"),
            details.lines,
        )
        assertTrue(details.any)
    }

    @Test
    fun `max findings cuts rows and counts the rest`() {
        val view = report(sample).toView(ReportViewOptions(maxFindings = 1))

        assertEquals(1, view.findings.size)
        assertEquals(3, view.more)
        assertEquals(4, view.counts.total)
    }

    @Test
    fun `russian plurals and labels`() {
        val many = List(5) { finding(Severity.ERROR, "r$it") } + finding(Severity.WARNING, "w")
        val view = report(many).toView(ReportViewOptions(language = "ru"))

        assertEquals("Не пройдено · 5 ошибок · 1 предупреждение", view.headline)
        assertEquals("Как исправить", view.labels.fix)
    }

    @Test
    fun `file links point at the merge request changes page`() {
        val gitlab = MrView("!7", "t", "https://gl/x/-/merge_requests/7")
        val github = MrView("#7", "t", "https://github.com/o/r/pull/7")

        assertEquals("https://gl/x/-/merge_requests/7/diffs", report(sample).toView(ReportViewOptions(mr = gitlab)).findings[1].link)
        assertEquals("https://github.com/o/r/pull/7/files", report(sample).toView(ReportViewOptions(mr = github)).findings[1].link)
        assertNull(report(sample).toView(ReportViewOptions(mr = gitlab)).findings[0].link)
    }

    @Test
    fun `where is shortened to the file name unless another file shares it and line links win`() {
        val findings = listOf(
            finding(Severity.ERROR, "a", Location.FileRef("app/x/Util.kt", 3)),
            finding(Severity.ERROR, "b", Location.FileRef("lib/x/Util.kt", 4, endLine = 6)),
            finding(Severity.ERROR, "c", Location.FileRef("app/Login.kt", 7)),
            finding(Severity.ERROR, "d"),
        )
        val mr = MrView("!7", "t", "https://gl/x/-/merge_requests/7", lineLinks = mapOf("app/Login.kt:7" to "https://gl/line"))

        val rows = report(findings).toView(ReportViewOptions(mr = mr)).findings

        assertEquals(listOf("MR", "Login.kt:7", "app/x/Util.kt:3", "lib/x/Util.kt:4-6"), rows.map { it.shortWhere })
        assertEquals(
            listOf(
                null,
                "https://gl/line",
                "https://gl/x/-/merge_requests/7/diffs",
                "https://gl/x/-/merge_requests/7/diffs"
            ),
            rows.map {
                it.link
            }
        )
        assertNull(rows[0].fingerprint)
        assertTrue(rows.drop(1).all { !it.fingerprint.isNullOrEmpty() })
    }

    @Test
    fun `styles hide internal metadata except verbose`() {
        val ai = sample[3]

        assertFalse(WellKnownMetadataKeys.AI_MODEL in ReportStyle.STANDARD.apply(ai).metadata)
        assertTrue(WellKnownMetadataKeys.AI_MODEL in ReportStyle.VERBOSE.apply(ai).metadata)
        assertEquals(ReportStyle.STANDARD, ReportStyle.of("concise"))
        assertEquals(ReportStyle.VERBOSE, ReportStyle.of("FULL"))
        assertEquals(ReportStyle.STANDARD, ReportStyle.of("nonsense"))
    }

    @Test
    fun `a root-level path shadowed by a nested same-name path keeps its full path`() {
        val findings = listOf(
            finding(Severity.ERROR, "a", Location.FileRef("build.gradle.kts", 1)),
            finding(Severity.ERROR, "b", Location.FileRef("app/build.gradle.kts", 2)),
        )

        val rows = report(findings).toView().findings

        assertEquals(setOf("build.gradle.kts:1", "app/build.gradle.kts:2"), rows.map { it.shortWhere }.toSet())
    }

    @Test
    fun `quiet hides the why from explanation and metadata while standard and verbose keep it`() {
        val withMetaWhy = finding(Severity.ERROR, "r", metadata = mapOf(WellKnownMetadataKeys.WHY to "meta why"))
        val withExplanation = withMetaWhy.copy(rule = "e", explanation = "because")
        val view = { style: ReportStyle -> report(listOf(withMetaWhy, withExplanation)).toView(ReportViewOptions(style = style)) }

        assertEquals(listOf(null, null), view(ReportStyle.QUIET).findings.map { it.why })
        assertEquals(setOf("meta why", "because"), view(ReportStyle.STANDARD).findings.mapNotNull { it.why }.toSet())
        assertEquals(setOf("meta why", "because"), view(ReportStyle.VERBOSE).findings.mapNotNull { it.why }.toSet())
    }

    @Test
    fun `standard hides the finding fingerprint but keeps the row marker and verbose keeps all fields`() {
        val located = finding(Severity.ERROR, "r", Location.FileRef("a.kt", 3)).copy(fingerprint = "fp", explanation = "x")

        assertNull(ReportStyle.STANDARD.apply(located).fingerprint)
        assertEquals("x", ReportStyle.STANDARD.apply(located).explanation)
        assertNull(ReportStyle.QUIET.apply(located).explanation)
        assertEquals(located, ReportStyle.VERBOSE.apply(located))
        assertTrue(
            !report(listOf(located))
                .toView()
                .findings
                .single()
                .fingerprint
                .isNullOrEmpty()
        )
    }
}
