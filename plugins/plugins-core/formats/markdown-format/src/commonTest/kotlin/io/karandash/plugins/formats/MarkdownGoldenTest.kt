package io.karandash.plugins.formats

import io.karandash.api.finding.AnalyzerProvenance
import io.karandash.api.finding.ComplianceItem
import io.karandash.api.finding.ComplianceStatus
import io.karandash.api.finding.EnricherProvenance
import io.karandash.api.finding.FilterProvenance
import io.karandash.api.finding.ProvenanceStatus
import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ReviewCoverage
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Mismatch
import io.karandash.models.finding.PresentationKeys
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.testfixtures.GoldenFiles
import io.karandash.testfixtures.TestFiles
import io.karandash.translations.polish.PolishTranslationModule
import io.karandash.translations.russian.RussianTranslationModule
import kotlinx.io.files.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden snapshots of the markdown summary and inline comments for representative findings and one large MR.
 *
 * Regenerate with `./gradlew :plugins:plugins-core:formats:markdown-format:jvmTest -Dkarandash.updateGolden=true`
 * and review the diff: a changed golden is a user-visible behavior change for the CHANGELOG.
 */
class MarkdownGoldenTest {
    @BeforeTest
    fun registerPolishPack() {
        PolishTranslationModule.register()
        RussianTranslationModule.register()
    }

    private fun assertGolden(
        name: String,
        actual: String,
    ) = GoldenFiles.assertMatches(ROOT, name, actual)

    private fun finding(
        message: String,
        severity: Severity = Severity.WARNING,
        rule: String = "r",
        path: String = "src/main/kotlin/App.kt",
        line: Int = 3,
        metadata: Map<String, String> = emptyMap(),
    ) = Finding(severity = severity, message = message, rule = rule, location = Location.FileRef(path, line), metadata = metadata)

    private fun report(
        findings: List<Finding>,
        checklist: List<ComplianceItem> = emptyList(),
        skipped: List<SkippedRuleInfo> = emptyList(),
        provenance: ReviewProvenance? = null,
    ) = ValidationReport(
        passed = findings.none { it.severity == Severity.ERROR },
        findings = findings,
        summary = findings.summarize(),
        groupedByLocation = emptyMap(),
        inlineEligible = emptyList(),
        summaryOnly = findings,
        complianceChecklist = checklist,
        explanations = emptyMap(),
        metadata = ReportMetadata(version = "test", timestamp = 0L),
        skippedRules = skipped,
        provenance = provenance,
    )

    private val representative = listOf(
        finding("Missing required label: 'needs-review'", Severity.ERROR, rule = "labels")
            .copy(suggestion = "Add the label via the MR sidebar.", explanation = "Labels route MRs through review."),
        finding(
            "The retry loop never backs off",
            rule = "aiReview",
            metadata = mapOf(
                WellKnownMetadataKeys.CONFIDENCE to "0.8",
                WellKnownMetadataKeys.CATEGORY to "reliability",
                WellKnownMetadataKeys.EXISTING_CODE to "while (true) retry()",
                WellKnownMetadataKeys.SUGGESTED_CODE to "repeat(3) { delay(it * 100L); retry() }",
            ),
        ),
        finding(
            "Large change touching payment code",
            rule = "risk-scoring",
            metadata = mapOf(WellKnownMetadataKeys.IS_RISK_ADVISORY to "true", WellKnownMetadataKeys.RISK_SCORE to "72"),
        ),
        finding("Line one\\nline two with a | pipe", Severity.INFO, rule = "style", line = 10),
    )

    @Test
    fun `summary with representative findings`() {
        assertGolden("summary-representative.md", formatSummaryMarkdown(representativeReport(), representative, inlineCount = 0))
    }

    @Test
    fun `summary with representative findings in Russian`() {
        val ru = formatSummaryMarkdown(representativeReport(), representative, inlineCount = 0, locale = SummaryLocale.of("ru-RU"))

        assertGolden("summary-representative-ru.md", ru)
    }

    @Test
    fun `an unknown report language renders the English summary`() {
        val en = formatSummaryMarkdown(representativeReport(), representative, inlineCount = 0)

        assertEquals(en, formatSummaryMarkdown(representativeReport(), representative, inlineCount = 0, locale = SummaryLocale.of("xx")))
    }

    private fun representativeReport(): ValidationReport {
        val checklist = listOf(
            ComplianceItem("labels", ComplianceStatus.FAILED),
            ComplianceItem("aiReview", ComplianceStatus.WARNING),
            ComplianceItem("titleFormat", ComplianceStatus.PASSED),
        )
        val skipped = listOf(
            SkippedRuleInfo("ticketCompliance", "budget exhausted", SkipCategory.AI_BUDGET_EXHAUSTED),
            SkippedRuleInfo("changelog", "draft MR"),
        )
        val provenance = ReviewProvenance(ReviewCoverage(totalFiles = 3, reviewedFiles = 2, chunksSkipped = 1))
        return report(representative, checklist, skipped, provenance)
    }

    /** Terse layout: a body over three lines is capped, prose "suggested code" gets no suggestion block. */
    private val terse = listOf(
        finding(
            "Null dereference on deleted users\\nThe lookup returns null for soft-deleted rows\\nand the caller uses !!",
            Severity.ERROR,
            rule = "review-comments",
            metadata = mapOf(WellKnownMetadataKeys.DIMENSION to "correctness"),
        ).copy(suggestion = "Return a fallback name.", explanation = "Soft-deleted users keep their id."),
        finding(
            "Name reads poorly",
            Severity.INFO,
            rule = "review-comments",
            metadata = mapOf(
                WellKnownMetadataKeys.EXISTING_CODE to "fun f()",
                WellKnownMetadataKeys.SUGGESTED_CODE to "Rename this to displayNameOf.",
            ),
        ).copy(suggestion = "Suggested change (CONV):\n```diff\n- fun f()\n+ Rename this to displayNameOf.\n```"),
    )

    @Test
    fun `inline comments for representative findings`() {
        val bodies = (representative + terse).joinToString("\n\n=====\n\n") { formatInlineCommentBody(it) }

        assertGolden("inline-representative.md", bodies)
    }

    @Test
    fun `summary of a large MR`() {
        val findings = (1..150).map { i ->
            finding(
                "Finding $i: the handler swallows the error and the retry loop never backs off",
                if (i % 4 == 0) Severity.ERROR else Severity.WARNING,
                rule = "rule-${i % 6}",
                path = "src/main/kotlin/module$i/File$i.kt",
                line = i,
            ).copy(suggestion = "Log the error and back off in File$i")
        }
        val skipped = (1..30).map { SkippedRuleInfo("skipped-rule-$it", "file pattern did not match any changed file") }
        val r = report(findings, skipped = skipped)

        assertGolden("summary-large-mr.md", formatSummaryMarkdown(r, r.summaryOnly, inlineCount = 0))
    }

    private val fullProvenance = ReviewProvenance(
        coverage = ReviewCoverage(totalFiles = 3, reviewedFiles = 2, chunksSkipped = 1),
        enrichers = listOf(
            EnricherProvenance("jira", ProvenanceStatus.SKIPPED, reason = "JIRA_TOKEN not set"),
            EnricherProvenance("ci", ProvenanceStatus.OK, tokens = 120),
        ),
        analyzers = listOf(
            AnalyzerProvenance("review-comments", model = "gpt-4o", inputTokens = 9000, outputTokens = 800, costUsd = 0.031, findings = 3),
            AnalyzerProvenance(
                "risk-scoring",
                model = "gpt-4o-mini",
                inputTokens = 1200,
                outputTokens = 60,
                costUsd = 0.0004,
                findings = 1
            ),
            AnalyzerProvenance("ticket-compliance", status = ProvenanceStatus.SKIPPED, reason = "budget exhausted"),
        ),
        filters = listOf(FilterProvenance(FilterProvenance.FEEDBACK, removed = 1), FilterProvenance("grounding", removed = 1)),
    )

    private fun transparencySummary(config: MarkdownTransparencyConfig): String {
        val r = report(representative, provenance = fullProvenance)
        return formatSummaryMarkdown(r, r.summaryOnly, inlineCount = 0, config)
    }

    @Test
    fun `summary with transparency off`() {
        assertGolden("summary-transparency-off.md", transparencySummary(MarkdownTransparencyConfig(TransparencySummary.OFF)))
    }

    @Test
    fun `transparency off renders byte for byte the summary without transparency`() {
        val r = report(representative, provenance = fullProvenance)
        val before = report(representative, provenance = ReviewProvenance(fullProvenance.coverage))

        assertEquals(
            formatSummaryMarkdown(before, before.summaryOnly, inlineCount = 0),
            transparencySummary(MarkdownTransparencyConfig(TransparencySummary.OFF)),
        )
        assertEquals(formatSummaryMarkdown(r, r.summaryOnly, inlineCount = 0), transparencySummary(MarkdownTransparencyConfig()))
    }

    @Test
    fun `transparency off with full provenance matches the pre-transparency golden file byte for byte`() {
        val checklist = listOf(
            ComplianceItem("labels", ComplianceStatus.FAILED),
            ComplianceItem("aiReview", ComplianceStatus.WARNING),
            ComplianceItem("titleFormat", ComplianceStatus.PASSED),
        )
        val skipped = listOf(
            SkippedRuleInfo("ticketCompliance", "budget exhausted", SkipCategory.AI_BUDGET_EXHAUSTED),
            SkippedRuleInfo("changelog", "draft MR"),
        )
        val r = report(representative, checklist, skipped, fullProvenance)

        val off = formatSummaryMarkdown(r, r.summaryOnly, inlineCount = 0, MarkdownTransparencyConfig(TransparencySummary.OFF))

        // read only, never regenerated here: the file is the summary from before T (unchanged since w14-m-done)
        assertEquals(TestFiles.readText(Path(ROOT, "summary-representative.md")), off.trimEnd() + "\n")
    }

    @Test
    fun `summary with transparency collapsed`() {
        assertGolden("summary-transparency-collapsed.md", transparencySummary(MarkdownTransparencyConfig()))
    }

    @Test
    fun `summary with transparency expanded and the flow diagram`() {
        val config = MarkdownTransparencyConfig(TransparencySummary.EXPANDED, flowDiagram = true)

        assertGolden("summary-transparency-expanded.md", transparencySummary(config))
    }

    @Test
    fun `summary with transparency expanded and the flow diagram in Russian`() {
        val r = report(representative, provenance = fullProvenance)
        val config = MarkdownTransparencyConfig(TransparencySummary.EXPANDED, flowDiagram = true)

        assertGolden(
            "summary-transparency-expanded-ru.md",
            formatSummaryMarkdown(r, r.summaryOnly, 0, config, locale = SummaryLocale.of("ru"))
        )
    }

    @Test
    fun `inline comment with the why footer`() {
        val why = representative[1].copy(
            metadata =
                representative[1].metadata + (WellKnownMetadataKeys.WHY to "review-comments on gpt-4o; context: ci")
        )

        assertGolden("inline-why.md", formatInlineCommentBody(why, inlineWhy = true))
    }

    private val walkthroughFindings = listOf(
        Finding(
            severity = Severity.INFO,
            message = "Adds login.",
            rule = "mr-summary",
            metadata = mapOf(
                WellKnownMetadataKeys.IS_MR_SUMMARY to "true",
                WellKnownMetadataKeys.WALKTHROUGH to
                    "| Unit | Files | Lines |\n|---|---|---|\n| `app` | `src/main/Login.kt` | +10/-2 |\n" +
                    "| `lib` | `src/Parser.kt` | +1/-1 |\n\n```mermaid\nsequenceDiagram\n" +
                    "  participant u0 as app\n  participant u1 as lib\n  u0->>u1: parse token\n```\n",
            ),
        ),
        finding("Null check missing"),
    )

    @Test
    fun `summary with the walkthrough collapsed`() {
        val r = report(walkthroughFindings)

        assertGolden("summary-walkthrough.md", formatSummaryMarkdown(r, r.summaryOnly, inlineCount = 0, walkthrough = true))
    }

    @Test
    fun `summary with transparency and the walkthrough in Russian`() {
        val r = report(walkthroughFindings, provenance = fullProvenance)
        val ru = formatSummaryMarkdown(r, r.summaryOnly, inlineCount = 0, walkthrough = true, locale = SummaryLocale.of("ru"))

        assertGolden("summary-transparency-ru.md", ru)
    }

    @Test
    fun `the inline why footer label follows the report language`() {
        val why = representative[1].copy(metadata = representative[1].metadata + (WellKnownMetadataKeys.WHY to "review-comments"))

        assertTrue("\n\nDlaczego: review-comments" in formatInlineCommentBody(why, inlineWhy = true, locale = SummaryLocale.of("pl")))
    }

    @Test
    fun `walkthrough off leaves the summary as it was`() {
        val r = report(walkthroughFindings)
        val plain = report(walkthroughFindings.map { it.copy(metadata = it.metadata - WellKnownMetadataKeys.WALKTHROUGH) })

        assertEquals(
            formatSummaryMarkdown(plain, plain.summaryOnly, inlineCount = 0),
            formatSummaryMarkdown(r, r.summaryOnly, inlineCount = 0),
        )
    }

    @Test
    fun `two suggested descriptions render once`() {
        fun described(title: String) =
            Finding(
                severity = Severity.INFO,
                message = "AI-generated description available.",
                rule = "mr-summary",
                metadata = mapOf(
                    WellKnownMetadataKeys.IS_GENERATED_DESCRIPTION to "true",
                    WellKnownMetadataKeys.SUGGESTED_TITLE to title,
                ),
            )
        val r = report(listOf(described("Add login"), described("Login screen"), finding("Null check missing")))
        val summary = formatSummaryMarkdown(r, r.summaryOnly, inlineCount = 0)

        assertEquals(1, summary.split("Suggested Description").size - 1, summary)
        assertTrue("Add login" in summary)
    }

    /** Pre-merge checklist: its rows render as one section of ticks apart from the review findings. */
    @Test
    fun `summary with a pre-merge checklist`() {
        val section = mapOf(
            PresentationKeys.SECTION to "pre-merge-checklist",
            PresentationKeys.SECTION_TITLE to "Pre-merge checklist",
            PresentationKeys.SECTION_ORDER to "0",
        )
        val rows = listOf(
            Severity.ERROR to "❌ No secrets logged — the token is printed in AuthClient",
            Severity.INFO to "✅ Public API documented — docs/api.md updated",
            Severity.INFO to "➖ Migrations reversible — no changed file matches its paths",
            Severity.WARNING to "❌ PAY-1: Audit row written — no audit insert in the refund path",
        ).map { (severity, message) -> Finding(severity = severity, message = message, rule = "pre-merge-checks", metadata = section) }
        val r = report(rows + finding("The retry loop never backs off", rule = "review-comments"))

        assertGolden("summary-pre-merge-checklist.md", formatSummaryMarkdown(r, r.summaryOnly, inlineCount = 0))
    }

    /** In `report.language: ru`: the rows as the checks executor writes them, the summary words from the ru table. */
    @Test
    fun `summary with a pre-merge checklist in Russian`() {
        val section = mapOf(
            PresentationKeys.SECTION to "pre-merge-checklist",
            PresentationKeys.SECTION_TITLE to "Чек-лист перед слиянием",
            PresentationKeys.SECTION_ORDER to "0",
        )
        val rows = listOf(
            Severity.ERROR to "❌ No secrets logged — the token is printed in AuthClient",
            Severity.INFO to "✅ Public API documented — docs/api.md updated",
            Severity.INFO to "➖ Migrations reversible — ни один изменённый файл не подходит под её пути",
            Severity.INFO to "➖ Соответствие задаче — нет связанной задачи",
        ).map { (severity, message) -> Finding(severity = severity, message = message, rule = "pre-merge-checks", metadata = section) }
        val r = report(rows + finding("The retry loop never backs off", rule = "review-comments"))
        val ru = formatSummaryMarkdown(r, r.summaryOnly, inlineCount = 0, locale = SummaryLocale.of("ru"))

        assertGolden("summary-pre-merge-checklist-ru.md", ru)
    }

    private val fixHint = finding(
        "MR title does not match the required format",
        Severity.ERROR,
        rule = "titleFormat",
        metadata = mapOf(
            WellKnownMetadataKeys.MISMATCH to Mismatch("feat add x", 4, "\": \"").toMetadataValue(),
            WellKnownMetadataKeys.EXPECTED_FORMAT to "(\"feat\" | \"fix\") \": \" .+",
        ),
    ).copy(suggestion = "rename the title to `feat: add x`")

    private val fixHintExplained = fixHint.copy(
        rule = "commitMessages",
        metadata = fixHint.metadata +
            mapOf(
                WellKnownMetadataKeys.MISMATCH to Mismatch("add x", 0, "\"feat\"", group = "type").toMetadataValue(),
                WellKnownMetadataKeys.FORMAT_EXPLAIN to "A type, a colon and a space, then the subject.",
                WellKnownMetadataKeys.FORMAT_DIAGRAM to "─┬─ \"feat\" ─┬─ \": \" ─ .+ ─\n └─ \"fix\" ──┘",
            ),
    )

    /** Four body lines over a cap of three: Format is dropped first. */
    private val fixHintCapped = fixHint.copy(
        message = "MR title does not match the required format\\nThe type prefix is missing its colon",
    )

    @Test
    fun `inline comments with a fix hint`() {
        val bodies = listOf(fixHint, fixHintExplained, fixHintCapped).joinToString("\n\n=====\n\n") { formatInlineCommentBody(it) }

        assertGolden("inline-fix-hint.md", bodies)
    }

    @Test
    fun `inline comment with a fix hint in Russian`() {
        assertGolden("inline-fix-hint-ru.md", formatInlineCommentBody(fixHintExplained, locale = SummaryLocale.of("ru")))
    }

    @Test
    fun `an inline body over the cap drops Format then Where and keeps How to fix`() {
        val dropsFormat = formatInlineCommentBody(fixHintCapped)
        val dropsBoth = formatInlineCommentBody(fixHintCapped.copy(explanation = "Changelogs are built from the type."))

        assertFalse("**Format:**" in dropsFormat, dropsFormat)
        assertTrue("**Where:**" in dropsFormat, dropsFormat)
        assertTrue("**How to fix:** rename the title" in dropsFormat, dropsFormat)
        assertFalse("**Where:**" in dropsBoth, dropsBoth)
        assertTrue("**How to fix:** rename the title" in dropsBoth, dropsBoth)
        assertTrue("Changelogs are built" in dropsBoth, dropsBoth)
    }

    @Test
    fun `summary with a fix hint`() {
        val r = report(listOf(fixHint, fixHintExplained, finding("Null check missing")))

        assertGolden("summary-fix-hint.md", formatSummaryMarkdown(r, r.summaryOnly, inlineCount = 0))
    }

    @Test
    fun `summary with a fix hint in Polish`() {
        val r = report(listOf(fixHintExplained))

        assertGolden("summary-fix-hint-pl.md", formatSummaryMarkdown(r, r.summaryOnly, inlineCount = 0, locale = SummaryLocale.of("pl")))
    }

    private companion object {
        val ROOT = Path(
            checkNotNull(TestFiles.findRepoRoot()) { "repo root not found" },
            "plugins/plugins-core/formats/markdown-format/src/commonTest/resources/golden",
        )
    }
}
