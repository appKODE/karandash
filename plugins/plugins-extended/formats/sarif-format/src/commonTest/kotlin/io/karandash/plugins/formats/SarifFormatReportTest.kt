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

/** Invocation, fingerprints, schema URL and fix-hint language for sarif. */
class SarifFormatReportTest {
    @BeforeTest
    fun registerRussianPack() {
        RussianTranslationModule.register()
    }

    private suspend fun sarif(
        finding: Finding,
        config: JsonObject = JsonObject(emptyMap()),
    ) = SarifFormatModule().format(createTestReport(findings = listOf(finding)), config).body

    @Test
    fun `the run reports a successful invocation and the stable schema URL`() =
        runTest {
            val body =
                sarif(
                    Finding(
                        severity = Severity.ERROR,
                        message = "bad",
                        rule = "r",
                        suggestion = "rename it",
                        location = Location.FileRef("a.kt", 2)
                    )
                )
            assertTrue("\"executionSuccessful\":true" in body.replace(" ", ""), body)
            assertTrue("https://json.schemastore.org/sarif-2.1.0.json" in body, body)
        }

    @Test
    fun `a fingerprinted finding carries a versioned partial fingerprint`() =
        runTest {
            val body =
                sarif(
                    Finding(
                        severity = Severity.ERROR,
                        message = "bad",
                        rule = "r",
                        suggestion = "rename it",
                        location = Location.FileRef("a.kt", 2)
                    ).copy(fingerprint = "abc123")
                )
            assertTrue("karandashFingerprint/v1" in body, body)
            assertTrue("abc123" in body, body)
        }

    @Test
    fun `fix hint label follows the language`() =
        runTest {
            assertTrue(
                "Как исправить: rename it" in
                    sarif(
                        Finding(
                            severity = Severity.ERROR,
                            message = "bad",
                            rule = "r",
                            suggestion = "rename it",
                            location = Location.FileRef("a.kt", 2)
                        ),
                        JsonObject(mapOf("language" to JsonPrimitive("ru")))
                    )
            )
        }
}
