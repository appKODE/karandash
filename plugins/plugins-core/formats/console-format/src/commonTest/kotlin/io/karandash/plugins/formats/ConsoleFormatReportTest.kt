package io.karandash.plugins.formats

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestReport
import io.karandash.translations.polish.PolishTranslationModule
import io.karandash.translations.russian.RussianTranslationModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** the console fix-hint label, verdict and fixed texts follow the report language. */
class ConsoleFormatReportTest {
    @BeforeTest
    fun registerPacks() {
        RussianTranslationModule.register()
        PolishTranslationModule.register()
    }

    @Test
    fun `fix hint label follows the language`() =
        runTest {
            val finding =
                Finding(
                    severity = Severity.ERROR,
                    message = "bad",
                    rule = "r",
                    suggestion = "rename it",
                    location = Location.FileRef("a.kt", 2),
                )
            val config = JsonObject(mapOf("language" to JsonPrimitive("ru")))
            val body = ConsoleFormatModule().format(createTestReport(findings = listOf(finding)), config).body
            assertTrue("Как исправить: rename it" in body, body)
        }

    @Test
    fun `the verdict follows the language in the concise and full layouts`() =
        runTest {
            val report = createTestReport(findings = listOf(Finding(severity = Severity.ERROR, message = "bad", rule = "r")))
            for (style in listOf("concise", "verbose")) {
                val config = JsonObject(mapOf("language" to JsonPrimitive("ru"), "style" to JsonPrimitive(style)))
                val body = ConsoleFormatModule().format(report, config).body
                assertTrue("Проверка: НЕ ПРОЙДЕНО" in body, body)
                assertFalse("FAILED" in body, body)
            }
            val english = ConsoleFormatModule().format(report, JsonObject(emptyMap())).body
            assertTrue("Validation FAILED" in english, english)
        }

    @Test
    fun `a clean run and the full layout use the report language`() =
        runTest {
            val ru = JsonObject(mapOf("language" to JsonPrimitive("ru")))
            assertTrue("Замечаний нет" in ConsoleFormatModule().format(createTestReport(findings = emptyList()), ru).body)
            val finding =
                Finding(
                    severity = Severity.WARNING,
                    message = "bad",
                    rule = "r",
                    explanation = "why",
                    location = Location.FileRef("a.kt", 2)
                )
            val verbose = JsonObject(mapOf("language" to JsonPrimitive("ru"), "verbose" to JsonPrimitive(true)))
            val body = ConsoleFormatModule().format(createTestReport(findings = listOf(finding, finding)), verbose).body
            listOf("Найдено 2 замечания:", "Предупреждения (2):", "Место: a.kt:2", "Пояснение: why", "Правило: r").forEach {
                assertTrue(it in body, body)
            }
            listOf("Found", "Warnings", "Location", "Explanation", "Rule:").forEach { assertFalse(it in body, body) }
        }

    @Test
    fun `the issue count takes the russian plural form`() {
        val expected = mapOf(
            1 to "замечание",
            2 to "замечания",
            5 to "замечаний",
            11 to "замечаний",
            21 to "замечание",
            22 to "замечания",
            25 to "замечаний"
        )
        expected.forEach { (n, word) -> assertEquals("Найдено $n $word:", ConsoleText.FOUND_ISSUES.count("ru", n)) }
        assertEquals("Found 1 issue(s):", ConsoleText.FOUND_ISSUES.count("en", 1))
        assertEquals("Found 21 issue(s):", ConsoleText.FOUND_ISSUES.count(null, 21))
    }
}
