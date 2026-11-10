package io.karandash.plugins.formats

import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.finding.LocationKey
import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.translations.polish.PolishTranslationModule
import io.karandash.translations.russian.RussianTranslationModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `markdown.language` (set from `report.language`) picks the summary strings; inline notes keep their tokens. */
class MarkdownReportLanguageTest {
    @BeforeTest
    fun registerPolishPack() {
        PolishTranslationModule.register()
        RussianTranslationModule.register()
    }

    private val finding = Finding(Severity.WARNING, "Сообщение остаётся как есть", "r", location = Location.FileRef("src/A.kt", 3))

    private val report = listOf(finding).let { findings ->
        ValidationReport(
            passed = true,
            findings = findings,
            summary = findings.summarize(),
            groupedByLocation = mapOf(LocationKey("src/A.kt", 3) to findings),
            inlineEligible = findings,
            summaryOnly = emptyList(),
            complianceChecklist = emptyList(),
            explanations = emptyMap(),
            metadata = ReportMetadata(version = "test", timestamp = 0L),
        )
    }

    private suspend fun format(language: String?) =
        MarkdownFormatModule().format(
            report,
            buildJsonObject {
                put("style", JsonPrimitive("verbose"))
                language?.let { put("language", JsonPrimitive(it)) }
            },
        )

    @Test
    fun `a Polish report language renders the Polish summary`() =
        runTest {
            val body = format("pl").body

            assertTrue("## Zgodność Karandash: ZALICZONO" in body, body)
            assertTrue("| Komentarze inline | 1 |" in body, body)
            assertTrue("Karandash Compliance" !in body, body)
        }

    @Test
    fun `inline notes keep the severity word and marker in every language`() =
        runTest {
            val en = format(null).inlineComments.single().body
            val ru = format("ru").inlineComments.single().body

            assertEquals(en, ru)
            assertTrue(" WARNING**" in ru && InlineFindingMarker.PREFIX in ru, ru)
        }

    @Test
    fun `an unset or English language keeps the English summary`() =
        runTest {
            assertEquals(format(null).body, format("en-US").body)
            assertTrue("## Karandash Compliance: PASSED" in format(null).body)
        }

    @Test
    fun `a cached finding is marked in the report language and in English unchanged`() {
        val cached = finding.copy(metadata = mapOf(WellKnownMetadataKeys.CACHED to "true"))
        val cachedReport = report.copy(findings = listOf(cached), inlineEligible = emptyList(), summaryOnly = listOf(cached))

        fun row(locale: SummaryLocale) =
            formatSummaryMarkdown(cachedReport, cachedReport.summaryOnly, inlineCount = 0, locale = locale)
                .lines()
                .single { "Сообщение остаётся как есть" in it }

        assertTrue("Сообщение остаётся как есть (из кэша)" in row(SummaryLocale.of("ru")), row(SummaryLocale.of("ru")))
        assertTrue("Сообщение остаётся как есть (cached)" in row(SummaryLocale.EN), row(SummaryLocale.EN))
    }
}
