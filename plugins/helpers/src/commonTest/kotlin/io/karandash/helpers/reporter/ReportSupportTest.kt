package io.karandash.helpers.reporter

import io.karandash.api.finding.BaselineComparison
import io.karandash.api.finding.ComplianceItem
import io.karandash.api.finding.ComplianceStatus
import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ReviewCoverage
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Attachment
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FindingCategory
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.report.MrView
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.testfixtures.TestPluginContext
import io.karandash.translations.polish.PolishTranslationModule
import io.karandash.translations.russian.RussianTranslationModule
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportSupportTest {
    @BeforeTest
    fun registerPolishPack() {
        PolishTranslationModule.register()
        RussianTranslationModule.register()
    }

    private fun report(
        findings: List<Finding> = emptyList(),
        baseline: BaselineComparison? = null,
        provenance: ReviewProvenance? = null,
    ) = ValidationReport(
        passed = findings.isEmpty(),
        findings = findings,
        summary = findings.summarize(),
        groupedByLocation = emptyMap(),
        inlineEligible = emptyList(),
        summaryOnly = findings,
        complianceChecklist = listOf(ComplianceItem("title", ComplianceStatus.PASSED)),
        explanations = emptyMap(),
        metadata = ReportMetadata(version = "2.0.0", timestamp = 0L),
        baselineComparison = baseline,
        provenance = provenance,
    )

    @Test
    fun `html escaping covers the five markup characters`() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;&#39;", "<a href=\"x\">&'".escapeHtml())
    }

    @Test
    fun `xml escaping drops control characters and lone surrogates but keeps pairs`() {
        val pair = "😀"

        assertEquals("a&amp;b&lt;&gt;&quot;&apos;", "a&b<>\"'".escapeXml())
        assertEquals("ab\tc\n", "a\u0001b\tc\n\u0002".escapeXml())
        assertEquals("x${pair}y", "x${pair}y".escapeXml())
        assertEquals("xy", "x\uD83Dy￾￿".escapeXml())
    }

    @Test
    fun `a custom template wins and bundled partials stay available`() {
        val view = report().toView()

        val out = renderReportTemplate(
            bundled = "bundled",
            view = view,
            escape = { it },
            override = TemplateOverride(template = "{{verdict}}|{{> p}}|{{extra}}"),
            extra = mapOf("extra" to JsonPrimitive("E")),
            bundledPartials = mapOf("p" to "P"),
        )

        assertEquals("Passed|P|E", out)
    }

    @Test
    fun `an invalid custom template falls back to the bundled one with a warning`() {
        val context = TestPluginContext()

        val out = renderReportTemplate(
            bundled = "{{verdict}}",
            view = report().toView(),
            escape = { it },
            override = TemplateOverride(template = "{{#open}}never closed"),
            logger = context.logger,
        )

        assertEquals("Passed", out)
        assertTrue(context.warnings.single().startsWith("report template"), context.warnings.toString())
    }

    @Test
    fun `a custom template over the output cap falls back to the bundled one`() {
        val context = TestPluginContext()

        val out = renderReportTemplate(
            bundled = "ok",
            view = report().toView(),
            escape = { it },
            override = TemplateOverride(template = "x".repeat(50)),
            logger = context.logger,
            maxOutputChars = 10,
        )

        assertEquals("ok", out)
        assertEquals(1, context.warnings.size)
    }

    @Test
    fun `a blank override template uses the bundled one`() {
        assertEquals("b", renderReportTemplate("b", report().toView(), { it }, TemplateOverride(template = "  ")))
    }

    @Test
    fun `attachment names lose control characters and fall back to attachment`() {
        assertEquals("a b c", Attachment("a\nb\tc", "x").displayName)
        assertEquals("attachment", Attachment("\n\t", "x").displayName)
    }

    @Test
    fun `facts list only the set fields and skip the default priority`() {
        val bare = Finding(severity = Severity.INFO, message = "m", rule = "r")
        val full = bare.copy(
            confidence = 0.5f,
            category = FindingCategory.SECURITY,
            priority = 7,
            relatedFindings = listOf("a", "b"),
            fingerprint = "fp",
            fixSuggestions = listOf(FixSuggestion.AddLabel("bug"), FixSuggestion.SetDraft(true)),
        )

        assertEquals(emptyList(), bare.facts())
        assertEquals(
            listOf(
                "confidence" to "0.5",
                "category" to "security",
                "priority" to "7",
                "relatedFindings" to "a, b",
                "fingerprint" to "fp",
                "fixSuggestions" to "add label 'bug'; mark as draft",
            ),
            full.facts(),
        )
    }

    @Test
    fun `every fix suggestion describes itself`() {
        assertEquals("remove label 'x'", FixSuggestion.RemoveLabel("x").describe())
        assertEquals("mark as ready", FixSuggestion.SetDraft(false).describe())
        assertEquals("set title to \"T\"", FixSuggestion.SetTitle("T").describe())
        assertEquals("update description", FixSuggestion.SetDescription("D").describe())
        assertEquals("do it", FixSuggestion.Custom(type = "t", description = "do it").describe())
        assertEquals("t", FixSuggestion.Custom(type = "t").describe())
    }

    @Test
    fun `fix suggestions describe themselves in the report language`() {
        assertEquals("добавить метку 'x'", FixSuggestion.AddLabel("x").describe("ru"))
        assertEquals("пометить как готовый", FixSuggestion.SetDraft(false).describe("ru"))
        assertEquals("сменить заголовок на «T»", FixSuggestion.SetTitle("T").describe("ru"))
        assertEquals("zaktualizować opis", FixSuggestion.SetDescription("D").describe("pl"))
        val finding = Finding(severity = Severity.WARNING, message = "m", rule = "r", fixSuggestions = listOf(FixSuggestion.SetDraft(true)))
        assertEquals(
            "пометить как черновик",
            report(listOf(finding))
                .toView(ReportViewOptions(language = "ru"))
                .findings
                .single()
                .fix
        )
    }

    @Test
    fun `range display strings show the end line only for multi line file locations`() {
        assertEquals("a.kt:3-5", Location.FileRef("a.kt", 3, 5).toRangeDisplayString())
        assertEquals("a.kt:3", Location.FileRef("a.kt", 3, 3).toRangeDisplayString())
        assertEquals("a.kt:3", Location.FileRef("a.kt", 3).toRangeDisplayString())
        assertEquals("commit:abc", Location.CommitRef("abc").toRangeDisplayString())
    }

    @Test
    fun `plain text body appends details and attachments`() {
        val finding = Finding(
            severity = Severity.ERROR,
            message = "msg",
            rule = "r",
            attachments = listOf(Attachment("map\n.md", "BODY")),
        )

        assertEquals("msg\n\nDET\n\n--- map .md ---\nBODY", finding.plainTextBody(details = "DET"))
        assertEquals("msg", Finding(severity = Severity.ERROR, message = "msg", rule = "r").plainTextBody())
    }

    @Test
    fun `counts use the plural form of each language`() {
        val en = ViewLanguage.of("en-US")
        val ru = ViewLanguage.of("ru")
        val pl = ViewLanguage.of("PL")

        assertEquals("1 error", ViewText.ERRORS.count(en, 1))
        assertEquals("2 errors", ViewText.ERRORS.count(en, 2))
        assertEquals("1 ошибка", ViewText.ERRORS.count(ru, 1))
        assertEquals("3 ошибки", ViewText.ERRORS.count(ru, 3))
        assertEquals("11 ошибок", ViewText.ERRORS.count(ru, 11))
        assertEquals("21 ошибка", ViewText.ERRORS.count(ru, 21))
        assertEquals("1 błąd", ViewText.ERRORS.count(pl, 1))
        assertEquals("4 błędy", ViewText.ERRORS.count(pl, 4))
        assertEquals("12 błędów", ViewText.ERRORS.count(pl, 12))
        assertEquals("Risk 5", ViewText.RISK.format(en, "5"))
        assertEquals("Риск 5", ViewText.RISK.format(ru, "5"))
        assertEquals("Ryzyko 5", ViewText.RISK.format(pl, "5"))
        assertEquals(ViewLanguage.EN, ViewLanguage.of(null))
    }

    @Test
    fun `every counted russian text takes the east slavic form for 1 2 5 11 21 22 and 25`() {
        val ru = ViewLanguage.of("ru")
        val numbers = listOf(1, 2, 5, 11, 21, 22, 25)

        assertEquals(
            listOf("1 ошибка", "2 ошибки", "5 ошибок", "11 ошибок", "21 ошибка", "22 ошибки", "25 ошибок"),
            numbers.map { ViewText.ERRORS.count(ru, it) },
        )
        assertEquals(
            listOf(
                "1 предупреждение",
                "2 предупреждения",
                "5 предупреждений",
                "11 предупреждений",
                "21 предупреждение",
                "22 предупреждения",
                "25 предупреждений",
            ),
            numbers.map { ViewText.WARNINGS.count(ru, it) },
        )
        ViewText.entries.filter { "|" in it.text(ViewLanguage.EN) }.forEach { text ->
            val forms = text.text(ru).split("|")
            assertEquals(3, forms.size, "$text needs three russian forms")
            val expected = listOf(0, 1, 2, 2, 0, 1, 2).zip(numbers) { form, n -> forms[form].replace("%d", n.toString()) }
            assertEquals(expected, numbers.map { text.count(ru, it) }, "$text")
        }
        val errors = List(22) { Finding(severity = Severity.ERROR, message = "m", rule = "r") }
        assertEquals("Не пройдено · 22 ошибки", report(errors).toView(ReportViewOptions(language = "ru")).headline)
    }

    @Test
    fun `russian and polish reports translate the verdict and counts`() {
        val findings = listOf(Finding(severity = Severity.ERROR, message = "m", rule = "r"))

        assertEquals("Не пройдено · 1 ошибка", report(findings).toView(ReportViewOptions(language = "ru")).headline)
        assertEquals("Niezaliczone · 1 błąd", report(findings).toView(ReportViewOptions(language = "pl")).headline)
    }

    @Test
    fun `the details section carries risk coverage and the delta`() {
        val risk = Finding(
            severity = Severity.INFO,
            message = "m",
            rule = "ai",
            metadata = mapOf(WellKnownMetadataKeys.IS_RISK_ADVISORY to "true", WellKnownMetadataKeys.RISK_SCORE to "7.9"),
        )
        val view = report(
            findings = listOf(risk),
            baseline = BaselineComparison(newFindings = 2, resolvedFindings = 1),
            provenance = ReviewProvenance(coverage = ReviewCoverage(totalFiles = 10, reviewedFiles = 8)),
        ).toView(ReportViewOptions(runNumber = 3))

        assertEquals(7, view.details.risk)
        assertEquals("AI reviewed 8/10 files", view.details.aiCoverage)
        assertEquals("1 fixed since last run", view.details.fixed)
        assertEquals("2 new, 1 fixed since last run", view.delta?.text)
        assertTrue(view.footer.contains("run 3 of this MR"), view.footer)
        assertTrue(view.details.lines.contains("Passed: title"), view.details.lines.toString())
        assertTrue(view.details.lines.contains("AI reviewed 8/10 files"), view.details.lines.toString())
    }

    @Test
    fun `finding rows link to the changes page and prefer the suggestion and explanation`() {
        val finding = Finding(
            severity = Severity.WARNING,
            message = "first\nsecond",
            rule = "r",
            location = Location.FileRef("a.kt", 4, 6),
            explanation = " because ",
            fixSuggestions = listOf(FixSuggestion.AddLabel("l")),
        )
        val noExplanation = finding.copy(explanation = null, metadata = mapOf(WellKnownMetadataKeys.WHY to "meta why"), suggestion = "do x")

        val github = report(listOf(finding)).toView(ReportViewOptions(mr = MrView(url = "https://gh/o/r/pull/9/")))
        val gitlab = report(listOf(noExplanation)).toView(ReportViewOptions(mr = MrView(url = "https://gl/g/p/-/merge_requests/9")))

        val row = github.findings.single()
        assertEquals("https://gh/o/r/pull/9/files", row.link)
        assertEquals("a.kt:4-6", row.where)
        assertEquals("add label 'l'", row.fix)
        assertEquals("because", row.why)
        assertEquals("first", row.message)
        val row2 = gitlab.findings.single()
        assertEquals("https://gl/g/p/-/merge_requests/9/diffs", row2.link)
        assertEquals("do x", row2.fix)
        assertEquals("meta why", row2.why)
        assertNull(
            report(listOf(finding))
                .toView()
                .findings
                .single()
                .link
        )
    }
}
