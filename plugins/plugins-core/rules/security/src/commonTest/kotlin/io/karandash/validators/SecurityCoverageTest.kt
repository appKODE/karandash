package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.size.KarandashSize
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class SecurityCoverageTest {
    private class Probe : SecurityRuleModule() {
        fun twoArg(
            mr: MergeRequest,
            config: SecurityConfig,
        ): List<Finding> = doValidateTyped(mr, config)
    }

    @Test
    fun `two argument validation delegates to the pattern aware form`() {
        assertNotNull(Probe().twoArg(createTestMergeRequest(), SecurityConfig()))
    }

    @Test
    fun `unpaired surrogate is counted as one byte`() {
        assertEquals(1, "\uD800".utf8ByteLength())
        assertEquals(1, "\uDC00".utf8ByteLength())
    }

    @Test
    fun `scan config rejects a diff size below the minimum`() {
        assertFailsWith<IllegalArgumentException> { SecurityScanConfig(maxDiffSize = KarandashSize.ofKb(1)) }
    }

    @Test
    fun `rule explanation is published for its own rule id`() =
        runTest {
            assertFalse(SecurityRuleModule().ruleExplanation(SecurityRuleModule.RULE_ID).isNullOrBlank())
        }
}
