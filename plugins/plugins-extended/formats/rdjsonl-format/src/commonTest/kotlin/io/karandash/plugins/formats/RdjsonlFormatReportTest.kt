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

/** Shared mapper, newline-terminated lines and fix-hint language for rdjsonl. */
class RdjsonlFormatReportTest {
    @BeforeTest
    fun registerRussianPack() {
        RussianTranslationModule.register()
    }

    @Test
    fun `every line ends with a newline and a missing location is omitted`() =
        runTest {
            val findings = listOf(
                Finding(severity = Severity.ERROR, message = "a", rule = "r1"),
                Finding(severity = Severity.INFO, message = "b", rule = "r2", location = Location.FileRef("f.kt", 1)),
            )
            val body = RdjsonlFormatModule().format(createTestReport(findings = findings), JsonObject(emptyMap())).body
            assertTrue(body.endsWith("}\n"), body)
            assertEquals(2, body.lines().count { it.isNotEmpty() })
            assertFalse("\"location\"" in body.lines().first(), body)
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
            val body = RdjsonlFormatModule()
                .format(
                    createTestReport(findings = listOf(finding)),
                    JsonObject(mapOf("language" to JsonPrimitive("ru")))
                ).body
            assertTrue("Как исправить: rename it" in body, body)
        }
}
