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

/** Shared reviewdog mapper and fix-hint language for rdjson. */
class RdjsonFormatReportTest {
    @BeforeTest
    fun registerRussianPack() {
        RussianTranslationModule.register()
    }

    @Test
    fun `a finding without a location omits it`() =
        runTest {
            val noLocation = Finding(severity = Severity.INFO, message = "m", rule = "r")
            val body = RdjsonFormatModule().format(createTestReport(findings = listOf(noLocation)), JsonObject(emptyMap())).body
            assertFalse("\"location\"" in body, body)
            assertTrue("\"diagnostics\"" in body, body)
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
            val body = RdjsonFormatModule()
                .format(
                    createTestReport(findings = listOf(finding)),
                    JsonObject(mapOf("language" to JsonPrimitive("ru")))
                ).body
            assertTrue("Как исправить: rename it" in body, body)
        }
}
