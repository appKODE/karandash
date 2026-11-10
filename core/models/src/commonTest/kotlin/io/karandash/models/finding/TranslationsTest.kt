package io.karandash.models.finding

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslationsTest {
    @AfterTest
    fun cleanup() {
        Translations.clear()
    }

    @Test
    fun `plural rules pick one few or many`() {
        assertEquals(listOf(0, 2, 2, 2, 2), listOf(1, 2, 5, 11, 21).map(PluralRule.ONE_OTHER::form))
        assertEquals(listOf(0, 1, 2, 2, 0, 1, 2), listOf(1, 2, 5, 11, 21, 22, 12).map(PluralRule.EAST_SLAVIC::form))
        assertEquals(listOf(0, 1, 2, 2, 2, 1, 2), listOf(1, 2, 5, 11, 21, 22, 12).map(PluralRule.WEST_SLAVIC::form))
    }

    @Test
    fun `count fills the number and falls back to the last form`() {
        assertEquals("2 błędy", PluralRule.WEST_SLAVIC.count("%d błąd|%d błędy|%d błędów", 2))
        assertEquals("3 x", PluralRule.EAST_SLAVIC.count("%d x", 3))
    }

    @Test
    fun `a pack serves its language by base tag and English never takes a pack`() {
        registerTestPolishPack()
        Translations.register(TranslationPack("en", PluralRule.ONE_OTHER, mapOf("Where" to "x")))
        Translations.register(TranslationPack(" ", PluralRule.ONE_OTHER, mapOf("Where" to "x")))
        assertEquals("Gdzie", Translations.translate("Where", "PL_pl"))
        assertNull(Translations.pack("en"))
        assertEquals(listOf("pl"), Translations.all().map { it.language })
        assertEquals("Where", Translations.text("Where", "ru"))
        registerTestRussianPack()
        assertEquals("Где", Translations.text("Where", "ru-RU"))
        assertEquals("Where", Translations.text("Where", "de"))
        assertEquals("pl", outputLanguage("pl-PL"))
        assertEquals("en", outputLanguage("de"))
    }

    @Test
    fun `a localized string uses the pack then its stored Polish then English`() {
        registerTestPolishPack()
        assertEquals("Podziel go.", LocalizedString("Split it.", "Разделите его.", "x", "Split it.", emptyList()).text("pl"))
        assertEquals("Podział", LocalizedString("Split", "Раздел", "Podział").text("pl"))
        assertEquals("Split", LocalizedString("Split").text("fr"))
        val joined = LocalizedString("a", "б", "c") + LocalizedString("d", "е", "f")
        assertEquals(LocalizedString("ad", "бе", "cf"), joined)
        assertTrue(joined.template == null)
    }
}
