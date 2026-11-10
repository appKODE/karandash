package io.karandash.normalize

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModuleIdNormalizerTest {
    @Test
    fun `kebab-case normalizes to lowercase alphanumeric`() {
        assertEquals("titleformat", ModuleIdNormalizer.normalize("title-format"))
    }

    @Test
    fun `camelCase normalizes to lowercase alphanumeric`() {
        assertEquals("titleformat", ModuleIdNormalizer.normalize("titleFormat"))
    }

    @Test
    fun `snake_case normalizes to lowercase alphanumeric`() {
        assertEquals("titleformat", ModuleIdNormalizer.normalize("title_format"))
    }

    @Test
    fun `PascalCase normalizes to lowercase alphanumeric`() {
        assertEquals("titleformat", ModuleIdNormalizer.normalize("TitleFormat"))
    }

    @Test
    fun `SCREAMING_SNAKE normalizes to lowercase alphanumeric`() {
        assertEquals("titleformat", ModuleIdNormalizer.normalize("TITLE_FORMAT"))
    }

    @Test
    fun `plain lowercase passes through unchanged`() {
        assertEquals("console", ModuleIdNormalizer.normalize("console"))
    }

    @Test
    fun `digits are preserved`() {
        assertEquals("rule42check", ModuleIdNormalizer.normalize("rule-42-check"))
    }

    @Test
    fun `empty string returns empty`() {
        assertEquals("", ModuleIdNormalizer.normalize(""))
    }

    @Test
    fun `matches returns true for equivalent identifiers`() {
        assertTrue(ModuleIdNormalizer.matches("title-format", "titleFormat"))
        assertTrue(ModuleIdNormalizer.matches("title_format", "TitleFormat"))
        assertTrue(ModuleIdNormalizer.matches("TITLE_FORMAT", "title-format"))
    }

    @Test
    fun `matches returns false for different identifiers`() {
        assertFalse(ModuleIdNormalizer.matches("title-format", "branch-naming"))
    }

    @Test
    fun `all naming conventions for bail-on-fail normalize identically`() {
        val expected = "bailonfail"
        assertEquals(expected, ModuleIdNormalizer.normalize("bail-on-fail"))
        assertEquals(expected, ModuleIdNormalizer.normalize("bail_on_fail"))
        assertEquals(expected, ModuleIdNormalizer.normalize("bailOnFail"))
        assertEquals(expected, ModuleIdNormalizer.normalize("BailOnFail"))
        assertEquals(expected, ModuleIdNormalizer.normalize("BAIL_ON_FAIL"))
    }

    @Test
    fun `resolve prefers the exact key then the first normalized match`() {
        val keys = listOf("titleLength", "goTitleLength")
        assertEquals("titleLength", ModuleIdNormalizer.resolve("titleLength", keys))
        assertEquals("titleLength", ModuleIdNormalizer.resolve("title-length", keys))
        assertEquals("titleLength", ModuleIdNormalizer.resolve("title_length", keys))
        assertEquals("titleLength", ModuleIdNormalizer.resolve("TitleLength", keys))
        assertEquals("goTitleLength", ModuleIdNormalizer.resolve("go-title-length", keys))
    }

    @Test
    fun `resolve returns null when nothing matches`() {
        assertEquals(null, ModuleIdNormalizer.resolve("sizeLimits", listOf("titleLength")))
    }
}
