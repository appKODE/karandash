package io.karandash.plugins.ai.pipeline

import io.karandash.logger.NoOpLogger
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.config.AiPipelineConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiReportGeneratorDigestTest {
    private val findings =
        List(4) { Finding(Severity.ERROR, "e", "security-scan") } + Finding(Severity.WARNING, "w", "style")

    private fun reports(vararg names: String) =
        AiReportGenerator(AiPipelineConfig(), NoOpLogger).generate(findings, null, null, "mr-1", names.toSet())

    @Test
    fun `manager digest pass rate is rounded`() {
        // 4 errors of 5 findings: 1 - 0.8 is 0.19999 and must print as 20
        val text = reports("digest-manager").getValue("digest-manager")

        assertTrue("Pass rate: 20%" in text, text)
        assertTrue("4 blocking issue(s) found across 2 rule(s)" in text, text)
    }

    @Test
    fun `only enabled digest audiences are generated`() {
        val all = reports("digest-developer", "digest-security")

        assertEquals(setOf("digest-developer", "digest-security"), all.keys)
        assertTrue("5 finding(s): 4 error(s), 1 warning(s), 0 info(s)" in all.getValue("digest-developer"))
        assertTrue("Security-related findings: 1" in all.getValue("digest-security"), all.getValue("digest-security"))
    }
}
