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

/** One testcase per finding, valid XML text and fix-hint language for the junit format. */
class JunitFormatReportTest {
    @BeforeTest
    fun registerRussianPack() {
        RussianTranslationModule.register()
    }

    private val finding =
        Finding(severity = Severity.ERROR, message = "bad", rule = "r", suggestion = "rename it", location = Location.FileRef("a.kt", 2))

    @Test
    fun `every finding is its own testcase`() =
        runTest {
            val other = finding.copy(message = "second", location = Location.FileRef("b.kt", 9))
            val body = JunitFormatModule().format(createTestReport(findings = listOf(finding, other)), JsonObject(emptyMap())).body
            assertEquals(2, Regex("<testcase ").findAll(body).count(), body)
            assertTrue("tests=\"2\"" in body, body)
            assertTrue("name=\"r a.kt:2\"" in body, body)
            assertTrue("name=\"r b.kt:9\"" in body, body)
        }

    @Test
    fun `characters XML forbids are dropped`() =
        runTest {
            val dirty = finding.copy(message = "a\uFFFEb\uD800c\u0001d")
            val body = JunitFormatModule().format(createTestReport(findings = listOf(dirty)), JsonObject(emptyMap())).body
            assertTrue("abcd" in body, body)
        }

    @Test
    fun `a valid surrogate pair is kept`() =
        runTest {
            val emoji = finding.copy(message = "a\uD83D\uDE00b")
            val body = JunitFormatModule().format(createTestReport(findings = listOf(emoji)), JsonObject(emptyMap())).body
            assertTrue("a\uD83D\uDE00b" in body, body)
        }

    @Test
    fun `fix hint label follows the language`() =
        runTest {
            val body = JunitFormatModule()
                .format(
                    createTestReport(findings = listOf(finding)),
                    JsonObject(mapOf("language" to JsonPrimitive("ru")))
                ).body
            assertTrue("Как исправить: rename it" in body, body)
            assertFalse("How to fix" in body, body)
        }
}
