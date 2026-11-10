package io.karandash.plugins.ai.core.reporters

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmartDigestGeneratorTest {
    private fun finding(
        rule: String,
        severity: Severity,
        message: String = "test"
    ) = Finding(
        severity = severity,
        message = message,
        rule = rule,
        location = Location.FileRef(path = "src/Foo.kt"),
    )

    @Test
    fun `no AI service returns static summary with aiEnhanced false`() =
        runTest {
            val generator = SmartDigestGenerator(aiService = null)
            val result = generator.generate(emptyList(), DigestAudience.DEVELOPER)
            assertFalse(result.aiEnhanced)
            assertTrue(result.summary.isNotEmpty())
        }

    @Test
    fun `developer audience static summary contains validation header`() =
        runTest {
            val generator = SmartDigestGenerator(aiService = null)
            val result = generator.generate(emptyList(), DigestAudience.DEVELOPER)
            assertTrue(result.summary.contains("Validation Summary"))
            assertEquals(DigestAudience.DEVELOPER, result.audience)
        }

    @Test
    fun `manager audience static summary contains risk assessment header`() =
        runTest {
            val generator = SmartDigestGenerator(aiService = null)
            val result = generator.generate(emptyList(), DigestAudience.MANAGER)
            assertTrue(result.summary.contains("Risk Assessment"))
            assertEquals(DigestAudience.MANAGER, result.audience)
        }

    @Test
    fun `security audience static summary contains security status header`() =
        runTest {
            val generator = SmartDigestGenerator(aiService = null)
            val result = generator.generate(emptyList(), DigestAudience.SECURITY)
            assertTrue(result.summary.contains("Security Status"))
            assertEquals(DigestAudience.SECURITY, result.audience)
        }

    @Test
    fun `computeStats counts severities correctly`() {
        val generator = SmartDigestGenerator(aiService = null)
        val findings = listOf(
            finding("rule-a", Severity.ERROR),
            finding("rule-b", Severity.ERROR),
            finding("rule-c", Severity.WARNING),
            finding("rule-d", Severity.INFO),
        )
        val stats = generator.computeStats(findings)
        assertEquals(4, stats.totalFindings)
        assertEquals(2, stats.errors)
        assertEquals(1, stats.warnings)
        assertEquals(1, stats.infos)
    }

    @Test
    fun `computeStats empty findings returns zero counts`() {
        val generator = SmartDigestGenerator(aiService = null)
        val stats = generator.computeStats(emptyList())
        assertEquals(0, stats.totalFindings)
        assertEquals(0, stats.errors)
        assertEquals(0, stats.warnings)
        assertEquals(0, stats.infos)
        assertEquals(0, stats.rulesTriggered)
        assertTrue(stats.topRules.isEmpty())
    }

    @Test
    fun `computeStats passRate is 1 for empty findings`() {
        val generator = SmartDigestGenerator(aiService = null)
        val stats = generator.computeStats(emptyList())
        assertEquals(1.0, stats.passRate)
    }

    @Test
    fun `computeStats passRate calculated correctly`() {
        val generator = SmartDigestGenerator(aiService = null)
        val findings = listOf(
            finding("rule-a", Severity.ERROR),
            finding("rule-b", Severity.WARNING),
            finding("rule-c", Severity.INFO),
            finding("rule-d", Severity.INFO),
        )
        val stats = generator.computeStats(findings)
        // 1 error out of 4 findings → passRate = 1 - 1/4 = 0.75
        assertEquals(0.75, stats.passRate)
    }

    @Test
    fun `computeStats topRules sorted by count descending`() {
        val generator = SmartDigestGenerator(aiService = null)
        val findings = listOf(
            finding("rule-a", Severity.ERROR),
            finding("rule-b", Severity.WARNING),
            finding("rule-b", Severity.ERROR),
            finding("rule-b", Severity.INFO),
        )
        val stats = generator.computeStats(findings)
        assertEquals("rule-b", stats.topRules.first())
    }

    @Test
    fun `generate with no AI sets audience correctly`() =
        runTest {
            val generator = SmartDigestGenerator(aiService = null)
            val result = generator.generate(emptyList(), DigestAudience.SECURITY)
            assertEquals(DigestAudience.SECURITY, result.audience)
        }

    @Test
    fun `manager summary includes pass rate and error count`() =
        runTest {
            val generator = SmartDigestGenerator(aiService = null)
            val findings = listOf(
                finding("rule-a", Severity.ERROR),
                finding("rule-b", Severity.WARNING),
            )
            val result = generator.generate(findings, DigestAudience.MANAGER)
            assertTrue(result.summary.contains("Pass rate"))
            assertTrue(result.summary.contains("blocking issue"))
        }

    @Test
    fun `developer summary lists top rules when present`() =
        runTest {
            val generator = SmartDigestGenerator(aiService = null)
            val findings = listOf(
                finding("title-format", Severity.ERROR),
                finding("title-format", Severity.WARNING),
            )
            val result = generator.generate(findings, DigestAudience.DEVELOPER)
            assertTrue(result.summary.contains("title-format"))
        }
}
