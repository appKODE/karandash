package io.karandash.plugins.formats

import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ReviewCoverage
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.skipReasonIn
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Finding
import io.karandash.translations.polish.PolishTranslationModule
import io.karandash.translations.russian.RussianTranslationModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Skip reasons, the AI status wrapper and dropped-hunk names follow `report.language`; English stays as written. */
class SkipReasonLanguageTest {
    @BeforeTest
    fun registerPacks() {
        PolishTranslationModule.register()
        RussianTranslationModule.register()
    }

    @Test
    fun `fixed skip reasons and their nested reasons translate and free-form reasons pass through`() {
        assertEquals("условие when не выполнено", skipReasonIn("when condition not met", "ru"))
        assertEquals("отсечено фильтром: есть метка пропуска", skipReasonIn("gated: skip label present", "ru"))
        assertEquals("ошибка провайдера: превышено время ожидания", skipReasonIn("provider error: timed out", "ru"))
        assertEquals(
            "ошибка провайдера: вызов не удался, см. лог задачи",
            skipReasonIn("provider error: call failed, see the job log", "ru")
        )
        assertEquals("ошибка провайдера: HTTP 500", skipReasonIn("provider error: HTTP 500", "ru"))
        assertEquals("тривиальный diff: только пробелы, lock-файл", skipReasonIn("trivial diff: whitespace-only, lockfile", "ru"))
        assertEquals(
            "файлов: 40, больше maxFiles=30; коммитов: 9, больше maxCommits=5",
            skipReasonIn("40 files exceeds maxFiles=30; 9 commits exceeds maxCommits=5", "ru"),
        )
        assertEquals("canary: MR не входит в 10% раскатки", skipReasonIn("canary: MR not in 10% rollout", "ru"))
        assertEquals("отключено политикой 'Hotfix'", skipReasonIn("disabled by policy 'Hotfix'", "ru"))
        assertEquals("pominięte przez kogoś", skipReasonIn("pominięte przez kogoś", "pl"))
        assertEquals("custom reason from a plugin", skipReasonIn("custom reason from a plugin", "ru"))
        assertEquals("gated: skip label present", skipReasonIn("gated: skip label present", null))
    }

    @Test
    fun `the AI status wrapper renders its reason in the report language`() {
        val provenance = ReviewProvenance(aiStatusTemplate = "AI: not run — %1", aiStatusArgs = listOf("provider error: timed out"))

        assertEquals("AI: not run — provider error: timed out", provenance.aiStatusIn(null))
        val ru = checkNotNull(provenance.aiStatusIn("ru"))
        assertTrue("ошибка провайдера: превышено время ожидания" in ru, ru)
        assertTrue("provider error" !in ru && "not run" !in ru, ru)
    }

    @Test
    fun `dropped hunks count by the plural rule and stay as written in English`() {
        val coverage =
            ReviewCoverage(totalFiles = 3, reviewedFiles = 2, dropped = listOf("B.kt", "C.kt (2 of 5 hunks)", "D.kt (1 of 21 hunks)"))

        assertTrue("left out: B.kt, C.kt (2 of 5 hunks), D.kt (1 of 21 hunks)" in coverage.describe(null))
        val ru = coverage.describe("ru")
        assertTrue("C.kt (2 из 5 фрагментов), D.kt (1 из 21 фрагмента)" in ru, ru)
        assertTrue("hunks" !in ru, ru)
        assertTrue("C.kt (2 z 5 fragmentów)" in coverage.describe("pl"))
    }

    @Test
    fun `the markdown skipped-rule list renders reasons in the report language`() =
        runTest {
            val skipped = listOf(SkippedRuleInfo("r1", "bail-on-fail triggered", SkipCategory.BAIL_ON_FAIL))
            val report = ValidationReport(
                passed = true,
                findings = emptyList(),
                summary = emptyList<Finding>().summarize(),
                groupedByLocation = emptyMap(),
                inlineEligible = emptyList(),
                summaryOnly = emptyList(),
                complianceChecklist = emptyList(),
                explanations = emptyMap(),
                metadata = ReportMetadata(version = "test", timestamp = 0L),
                skippedRules = skipped,
            )

            suspend fun body(language: String?) =
                MarkdownFormatModule()
                    .format(
                        report,
                        buildJsonObject {
                            put("style", JsonPrimitive("verbose"))
                            language?.let { put("language", JsonPrimitive(it)) }
                        },
                    ).body

            assertTrue("- `r1`: bail-on-fail triggered" in body(null), body(null))
            val ru = body("ru")
            assertTrue("- `r1`: сработал bail-on-fail" in ru, ru)
        }
}
