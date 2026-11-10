package io.karandash.validators

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Severity
import io.karandash.size.KarandashSize
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecurityKeyBehaviourTest {
    private val secret = listOf("TOPSECRET\\w+")

    private fun titleFindings(config: SecurityConfig) =
        SecurityValidator(config).validate(createTestMergeRequest(title = "see TOPSECRETabc")).filter { it.message.contains("MR title") }

    private fun bigDiff(): List<FileChange> =
        listOf(FileChange("a.txt", ChangeType.MODIFIED, additions = 1, diffContent = "+" + "x".repeat(70_000)))

    @Test
    fun `scanForSecrets false stops the secret scan`() {
        val base = SecurityConfig(secretPatterns = secret, sensitiveFiles = emptyList())
        assertEquals(1, titleFindings(base).size)
        assertEquals(0, titleFindings(base.copy(scanForSecrets = false)).size)
    }

    @Test
    fun `secretPatterns decides what counts as a secret`() {
        assertEquals(1, titleFindings(SecurityConfig(secretPatterns = secret, sensitiveFiles = emptyList())).size)
        assertEquals(0, titleFindings(SecurityConfig(secretPatterns = listOf("NOPE\\d+"), sensitiveFiles = emptyList())).size)
    }

    @Test
    fun `sensitiveFiles flags a changed file that matches`() {
        val mr = createTestMergeRequest(changes = listOf(FileChange("keys/server.pem", ChangeType.ADDED)))
        assertEquals(1, SecurityValidator(SecurityConfig(sensitiveFiles = listOf("**/*.pem"))).validate(mr).size)
        assertEquals(0, SecurityValidator(SecurityConfig(sensitiveFiles = listOf("**/*.key"))).validate(mr).size)
    }

    @Test
    fun `scan maxDiffSize skips a diff above the limit`() {
        val mr = createTestMergeRequest(changes = bigDiff())
        val small = SecurityConfig(secretPatterns = secret, sensitiveFiles = emptyList(), scan = SecurityScanConfig(KarandashSize.ofKb(64)))
        val large = small.copy(scan = SecurityScanConfig(KarandashSize.ofKb(128)))
        assertTrue(
            SecurityValidator(small)
                .validate(mr)
                .single()
                .message
                .contains("too large to scan")
        )
        assertEquals(emptyList(), SecurityValidator(large).validate(mr))
    }

    @Test
    fun `scan with a bigger maxDiffSize scans the diff`() {
        val mr = createTestMergeRequest(changes = bigDiff())
        val config = SecurityConfig(secretPatterns = secret, sensitiveFiles = emptyList(), scan = SecurityScanConfig(KarandashSize.ofMb(1)))
        assertEquals(emptyList(), SecurityValidator(config).validate(mr))
    }

    @Test
    fun `severity secretFound lowers the secret finding to a warning`() {
        val base = SecurityConfig(secretPatterns = secret, sensitiveFiles = emptyList())
        assertEquals(Severity.ERROR, titleFindings(base).single().severity)
        val lowered = base.copy(severities = SecuritySeverityConfig(secretFound = "warning"))
        assertEquals(Severity.WARNING, titleFindings(lowered).single().severity)
    }

    @Test
    fun `severity largeDiffSkipped raises the skipped diff finding to an error`() {
        val mr = createTestMergeRequest(changes = bigDiff())
        val base = SecurityConfig(secretPatterns = secret, sensitiveFiles = emptyList(), scan = SecurityScanConfig(KarandashSize.ofKb(64)))
        assertEquals(Severity.WARNING, SecurityValidator(base).validate(mr).single().severity)
        val raised = base.copy(severities = SecuritySeverityConfig(largeDiffSkipped = "error"))
        assertEquals(Severity.ERROR, SecurityValidator(raised).validate(mr).single().severity)
    }
}
