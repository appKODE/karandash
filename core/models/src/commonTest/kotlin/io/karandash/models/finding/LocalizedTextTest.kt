package io.karandash.models.finding

import io.karandash.models.support.WellKnownMetadataKeys
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class LocalizedTextTest {
    private enum class Text(
        override val en: String,
    ) : LocalizedText {
        MSG("File {0} has {1} lines"),
        FIX("Split it."),
    }

    @BeforeTest
    fun registerPack() {
        registerTestPolishPack()
        registerTestRussianPack()
    }

    private val base = Finding(severity = Severity.ERROR, message = "x", rule = "r", metadata = mapOf("k" to "v"))
    private val localized = base.withText(message = Text.MSG.of("{1}", 7), suggestion = Text.FIX.of())

    @Test
    fun `English stays in the fields and the key is removed for every language`() {
        assertEquals("File {1} has 7 lines", localized.message)
        assertEquals("Split it.", localized.suggestion)
        assertEquals(base.copy(message = "File {1} has 7 lines", suggestion = "Split it."), localized.inLanguage(null))
        assertEquals(base.copy(message = "File {1} has 7 lines", suggestion = "Split it."), localized.inLanguage("de"))
    }

    @Test
    fun `ru and pl texts replace the English ones`() {
        val ru = localized.inLanguage("ru-RU")
        assertEquals("Файл {1}: 7 строк", ru.message)
        assertEquals("Разделите его.", ru.suggestion)
        assertEquals(mapOf("k" to "v"), ru.metadata)
        val pl = localized.inLanguage("pl")
        assertEquals("Plik {1} ma 7 linii", pl.message)
        assertEquals("Podziel go.", pl.suggestion)
    }

    @Test
    fun `a text changed after withText is kept`() {
        val changed = localized.copy(message = "rewritten").inLanguage("ru")
        assertEquals("rewritten", changed.message)
        assertEquals("Разделите его.", changed.suggestion)
    }

    @Test
    fun `a finding without localized text is returned as is`() {
        assertSame(base, base.inLanguage("ru"))
    }

    @Test
    fun `withFixedText localizes only texts equal to a fixed English text`() {
        val finding = base.copy(message = "keep me", suggestion = "Split it.").withFixedText(Text.entries)
        assertEquals("keep me", finding.inLanguage("pl").message)
        assertEquals("Podziel go.", finding.inLanguage("pl").suggestion)
        assertSame(base, base.withFixedText(Text.entries))
    }

    @Test
    fun `malformed localized text leaves English`() {
        val bad = base.copy(metadata = mapOf(WellKnownMetadataKeys.LOCALIZED_TEXT to "not json"))
        assertEquals(base.copy(metadata = emptyMap()), bad.inLanguage("ru"))
    }
}
