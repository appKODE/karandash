package io.karandash.plugins.ai.core

import io.karandash.models.support.KarandashJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguagePromptOverlaysTest {
    @Test
    fun `default overlay lookup ignores case and covers each built-in language`() {
        listOf("kotlin", "python", "go", "typescript", "java", "rust").forEach { language ->
            assertTrue(LanguagePromptOverlays.get(language).orEmpty().isNotBlank(), language)
        }
        assertEquals(LanguagePromptOverlays.get("kotlin"), LanguagePromptOverlays.get("Kotlin"))
    }

    @Test
    fun `custom overlay overrides a default and adds a new language`() {
        val overlays = LanguagePromptOverlays(mapOf("kotlin" to "CUSTOM KT", "swift" to "CUSTOM SWIFT"))

        assertEquals("CUSTOM KT", overlays.get("KOTLIN"))
        assertEquals("CUSTOM SWIFT", overlays.get("swift"))
        assertEquals(LanguagePromptOverlays.get("go"), overlays.get("go"))
    }

    @Test
    fun `unknown or null language has no overlay`() {
        assertNull(LanguagePromptOverlays.get(null))
        assertNull(LanguagePromptOverlays.get("cobol"))
    }

    @Test
    fun `output language instruction names the language and skips english`() {
        assertNull(LanguagePromptOverlays.outputLanguageInstruction("en"))
        assertNull(LanguagePromptOverlays.outputLanguageInstruction(" English "))
        assertNull(LanguagePromptOverlays.outputLanguageInstruction("en-US"))
        assertTrue("in Russian (ru)" in LanguagePromptOverlays.outputLanguageInstruction("ru").orEmpty())
        assertTrue("in Polish (pl)" in LanguagePromptOverlays.outputLanguageInstruction("pl-PL").orEmpty())
        assertTrue("in German (de)" in LanguagePromptOverlays.outputLanguageInstruction("german").orEmpty())
        assertTrue("in Klingon." in LanguagePromptOverlays.outputLanguageInstruction("Klingon").orEmpty())
    }

    @Test
    fun `withOutputLanguage appends the instruction and keeps an English prompt as it is`() {
        assertEquals("P", LanguagePromptOverlays.withOutputLanguage("P", null))
        assertEquals("P", LanguagePromptOverlays.withOutputLanguage("P", "English"))
        val ru = LanguagePromptOverlays.withOutputLanguage("P", "ru")
        assertTrue(ru.startsWith("P\n\n") && "in Russian (ru)" in ru, ru)
    }

    @Test
    fun `responseLanguageOf reads the ai block or a bare block and ignores blank`() {
        fun of(json: String) = LanguagePromptOverlays.responseLanguageOf(KarandashJson.Default.parseToJsonElement(json))
        assertEquals("ru", of("""{"ai": {"responseLanguage": "ru"}}"""))
        assertEquals("pl", of("""{"responseLanguage": "pl"}"""))
        assertNull(of("""{"ai": {"responseLanguage": " "}}"""))
        assertNull(LanguagePromptOverlays.responseLanguageOf(null))
    }
}
