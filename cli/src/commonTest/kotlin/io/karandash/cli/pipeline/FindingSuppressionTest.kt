package io.karandash.cli.pipeline

import io.karandash.api.exception.ConfigParseException
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.platform.writeText
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** inline `karandash-disable <rule>` and `--baseline` suppression. */
class FindingSuppressionTest {
    private val size = Finding(severity = Severity.ERROR, message = "too big", rule = "sizeLimits")
    private val title = Finding(severity = Severity.WARNING, message = "bad title", rule = "title")

    @Test
    fun `inline disable drops the named rule only`() {
        val result = FindingSuppression.apply(listOf(size, title), "text\nkarandash-disable sizeLimits\n", null, false)

        assertEquals(listOf(title), result.findings)
        assertEquals(setOf("sizeLimits"), result.disabledRules)
        assertNull(result.comparison)
    }

    @Test
    fun `inline disable accepts a comma list and ignores trailing prose`() {
        assertEquals(setOf("a", "b"), FindingSuppression.disabledRules("karandash-disable a, b"))
        assertEquals(setOf("a"), FindingSuppression.disabledRules("karandash-disable a because legacy, ok"))
        assertEquals(emptySet(), FindingSuppression.disabledRules("nothing here"))
    }

    @Test
    fun `inline disable with a colon names the rules not the colon`() {
        assertEquals(setOf("sizeLimits", "titleFormat"), FindingSuppression.disabledRules("karandash-disable: sizeLimits, titleFormat"))
        assertEquals(setOf("sizeLimits"), FindingSuppression.disabledRules("karandash-disable:sizeLimits"))
        assertEquals(emptySet(), FindingSuppression.disabledRules("karandash-disable: (all of them)"))
    }

    @Test
    fun `baseline suppresses known findings and counts new resolved unchanged`() {
        val file = Path(TestFiles.createTestTempDirectory("baseline"), "baseline.json").toString()
        FindingSuppression.apply(listOf(size, title), null, file, updateBaseline = true)

        val fresh = Finding(severity = Severity.WARNING, message = "new", rule = "labels")
        val result = FindingSuppression.apply(listOf(size, fresh), null, file, updateBaseline = false)

        assertEquals(listOf(fresh), result.findings)
        val comparison = assertNotNull(result.comparison)
        assertEquals(1, comparison.newFindings)
        assertEquals(1, comparison.unchangedFindings)
        assertEquals(1, comparison.resolvedFindings)
        assertTrue(comparison.baselineTimestamp > 0, "baselineTimestamp is the update-baseline run time")
    }

    @Test
    fun `a baseline written before createdAt existed still loads with timestamp 0`() {
        val file = Path(TestFiles.createTestTempDirectory("baseline"), "baseline.json")
        file.writeText("{\"fingerprints\":[]}")

        val comparison = assertNotNull(FindingSuppression.apply(listOf(size), null, file.toString(), false).comparison)
        assertEquals(0, comparison.baselineTimestamp)
    }

    @Test
    fun `a baselined finding whose anchored line moved from 40 to 43 stays unchanged`() {
        val file = Path(TestFiles.createTestTempDirectory("baseline-anchor"), "baseline.json").toString()

        fun at(line: Int) =
            Finding(
                severity = Severity.WARNING,
                message = "todo left",
                rule = "todo",
                location = Location.FileRef("src/A.kt", line),
                metadata = mapOf(WellKnownMetadataKeys.ANCHOR_FINGERPRINT to "anchor-1"),
            )
        FindingSuppression.apply(listOf(at(40)), null, file, updateBaseline = true)

        val result = FindingSuppression.apply(listOf(at(43)), null, file, updateBaseline = false)

        assertEquals(emptyList(), result.findings)
        val comparison = assertNotNull(result.comparison)
        assertEquals(1, comparison.unchangedFindings)
        assertEquals(0, comparison.newFindings)
        assertEquals(0, comparison.resolvedFindings)
    }

    @Test
    fun `missing baseline file suppresses nothing`() {
        val file = Path(TestFiles.createTestTempDirectory("baseline-missing"), "none.json").toString()

        val result = FindingSuppression.apply(listOf(size), null, file, updateBaseline = false)

        assertEquals(listOf(size), result.findings)
    }

    @Test
    fun `update baseline without a path is an error`() {
        assertFailsWith<ConfigParseException> { FindingSuppression.apply(listOf(size), null, null, updateBaseline = true) }
    }

    @Test
    fun `malformed baseline file is a config error not a crash`() {
        val file = Path(TestFiles.createTestTempDirectory("baseline-bad"), "bad.json")
        file.writeText("{ not json")

        val error = assertFailsWith<ConfigParseException> {
            FindingSuppression.apply(listOf(size), null, file.toString(), updateBaseline = false)
        }

        assertTrue("not a valid baseline" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `inline disable matches kebab snake and camel rule ids`() {
        listOf("size-limits", "size_limits", "sizeLimits").forEach { id ->
            val result = FindingSuppression.apply(listOf(size, title), "karandash-disable $id", null, false)
            assertEquals(listOf(title), result.findings, id)
        }
    }
}
