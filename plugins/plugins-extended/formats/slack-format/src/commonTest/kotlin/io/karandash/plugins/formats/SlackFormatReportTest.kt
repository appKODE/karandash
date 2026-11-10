package io.karandash.plugins.formats

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestReport
import io.karandash.translations.russian.RussianTranslationModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Escaped, bounded section text and fix-hint language for slack. */
class SlackFormatReportTest {
    @BeforeTest
    fun registerRussianPack() {
        RussianTranslationModule.register()
    }

    @Test
    fun `an escaped entity is never cut in half`() {
        val finding = Finding(severity = Severity.ERROR, message = "<".repeat(5000), rule = "r")
        val text = buildFindingText("x", finding)
        assertTrue(text.length <= 3000, "length ${text.length}")
        assertTrue(Regex("&(?!amp;|lt;|gt;)").findAll(text).none(), text.takeLast(20))
        assertFalse(text.endsWith("&") || text.endsWith("&l") || text.endsWith("&lt"), text.takeLast(20))
    }

    @Test
    fun `ampersands and angle brackets are escaped inside a cut message`() {
        val finding = Finding(severity = Severity.ERROR, message = "&<>".repeat(2000), rule = "r")
        val text = buildFindingText("x", finding)
        assertTrue(text.length <= 3000, "length ${text.length}")
        assertTrue("&amp;&lt;&gt;" in text, text.take(60))
        assertTrue(Regex("&(?!amp;|lt;|gt;)").findAll(text).none(), text.takeLast(20))
    }

    @Test
    fun `a cut inside a code fence closes the fence`() {
        val finding = Finding(
            severity = Severity.ERROR,
            message = "m",
            rule = "r",
            attachments = listOf(
                io.karandash.models.finding
                    .Attachment("log.txt", "x".repeat(5000))
            ),
        )
        val text = buildFindingText("x", finding)
        assertTrue(text.length <= 3000, "length ${text.length}")
        assertEquals(1, text.split("```").size % 2, text.takeLast(30))
    }

    private fun withAttachment(content: String) =
        Finding(
            severity = Severity.ERROR,
            message = "m",
            rule = "r",
            attachments = listOf(
                io.karandash.models.finding
                    .Attachment("log.txt", content)
            ),
        )

    @Test
    fun `a cut never splits a surrogate pair and leaves a truncation marker`() {
        val emoji = "\uD83D\uDE00"
        val text = buildFindingText("x", withAttachment(emoji.repeat(3000)))
        assertTrue(text.length <= 3000, "length ${text.length}")
        assertFalse(text.any { it.isHighSurrogate() && !text[text.indexOf(it) + 1].isLowSurrogate() }, "lone high surrogate")
        assertTrue("\u2026" in text, text.takeLast(30))
        assertEquals(1, text.split("```").size % 2, text.takeLast(30))
    }

    @Test
    fun `attachment text containing a fence cannot break the fences`() {
        val text = buildFindingText("x", withAttachment("a\n```\n<!channel> " + "y".repeat(5000)))
        assertTrue(text.length <= 3000, "length ${text.length}")
        assertEquals(1, text.split("```").size % 2, text.takeLast(30))
        assertEquals(2, text.split("```").size - 1, "exactly the attachment's own fence pair")
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
                    location = Location.FileRef("a.kt", 2)
                )
            val body = SlackFormatModule()
                .format(
                    createTestReport(findings = listOf(finding)),
                    JsonObject(mapOf("language" to JsonPrimitive("ru")))
                ).body
            assertTrue("Как исправить: rename it" in body, body)
        }

    @Test
    fun `a compact finding cut at the section limit keeps whole entities`() =
        runTest {
            val message = "a".repeat(2990) + "&&&&" + "b".repeat(106)
            val report = createTestReport(findings = listOf(Finding(severity = Severity.ERROR, message = message, rule = "r")))
            val body = SlackFormatModule().format(report, JsonObject(emptyMap())).body
            val section = Regex("\"text\":\"([^\"]*aaaa[^\"]*)\"")
                .find(body)
                ?.groupValues
                ?.get(1)
                .orEmpty()
            assertTrue(section.endsWith("\u2026"), section.takeLast(20))
            assertTrue(Regex("&(?!amp;|lt;|gt;)").findAll(section).none(), section.takeLast(20))
            assertTrue(section.length <= 3000, "length ${section.length}")
        }

    @Test
    fun `the verbose layout renders its fixed texts in the report language and keeps English unchanged`() =
        runTest {
            val findings = (1..12).map { Finding(severity = Severity.ERROR, message = "m$it", rule = "r") }

            suspend fun slack(language: String?): String {
                val config = mapOf("style" to JsonPrimitive("verbose")) + listOfNotNull(language?.let { "language" to JsonPrimitive(it) })
                return SlackFormatModule().format(createTestReport(findings = findings), JsonObject(config)).body
            }
            val english = slack(null)
            val englishTexts = listOf(
                "Karandash Validation: :x: ",
                "*Errors:* 12 | *Warnings:* 0 | *Info:* 0",
                "_... and 2 more findings_",
                "_Generated by karandash at ",
            )
            englishTexts.forEach { assertTrue(it in english, it) }
            val passed = SlackFormatModule()
                .format(
                    createTestReport(findings = emptyList()),
                    JsonObject(mapOf("style" to JsonPrimitive("verbose")))
                ).body
            assertTrue("All validation checks passed! :tada:" in passed, passed)

            val russian = slack("ru")

            assertTrue("*Ошибки:* 12 | *Предупреждения:* 0 | *Инфо:* 0" in russian, russian)
            assertTrue("_... и ещё 2 замечания_" in russian && "Проверка karandash: :x: " in russian, russian)
            englishTexts.forEach { assertFalse(it in russian, it) }
        }
}
