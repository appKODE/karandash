package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.helpers.reporter.ReportMediaKeys
import io.karandash.models.finding.PluralRule
import io.karandash.models.finding.TranslationPack
import io.karandash.models.finding.Translations
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EmailSubjectTest {
    @BeforeTest
    fun registerRussianPack() {
        // A one-entry pack: a standalone plugin must not depend on the Russian pack module; its full coverage of
        // EmailText is checked by TranslationPackCoverageTest.
        Translations.register(
            TranslationPack("ru", PluralRule.EAST_SLAVIC, mapOf(EmailText.DEFAULT_SUBJECT.en to "Отчёт о проверке Karandash")),
        )
    }

    private val bare = FormattedReport(formatId = "html", body = "<p>report</p>")

    @Test
    fun `the generic subject follows the report language and English stays unchanged`() {
        assertEquals("Karandash Validation Report", EmailOutputModule.subjectOf(EmailOutputConfig(), bare))
        assertEquals("Отчёт о проверке Karandash", EmailOutputModule.subjectOf(EmailOutputConfig(language = "ru"), bare))
        val withSubject = bare.copy(metadata = mapOf(ReportMediaKeys.SUBJECT to "[karandash] PASSED"))
        assertEquals("[karandash] PASSED", EmailOutputModule.subjectOf(EmailOutputConfig(language = "ru"), withSubject))
        assertEquals("mine", EmailOutputModule.subjectOf(EmailOutputConfig(subject = "mine", language = "ru"), withSubject))
    }
}
