package io.karandash.validators

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.size.KarandashSize
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityRegressionTest {
    private val secretPattern = listOf("password\\s*=\\s*\\S+")

    @Test
    fun `unknown severity is rejected at config time`() {
        assertFailsWith<IllegalArgumentException> { SecuritySeverityConfig(secretFound = "fatal") }
        assertFailsWith<IllegalArgumentException> { SecuritySeverityConfig(largeDiffSkipped = "") }
    }

    @Test
    fun `commit message finding has no file location`() {
        val commit = Commit(
            sha = "abcdef1234567890",
            message = "password = secret123",
            author = CommitAuthor(name = "Test User", email = "test@example.com"),
            timestamp = "2024-01-01T12:00:00Z",
        )
        val findings = SecurityValidator(SecurityConfig(secretPatterns = secretPattern, sensitiveFiles = emptyList()))
            .validate(createTestMergeRequest(commits = listOf(commit)))
        assertEquals(1, findings.size)
        assertNull(findings[0].location)
        assertTrue(findings[0].message.contains("abcdef1"))
    }

    @Test
    fun `diff size is compared in bytes not chars`() {
        // 40_000 two-byte chars: 40_000 chars < 64 KiB but 80_000 bytes > 64 KiB.
        val diff = "+" + "я".repeat(40_000)
        val config = SecurityConfig(
            secretPatterns = secretPattern,
            sensitiveFiles = emptyList(),
            scan = SecurityScanConfig(maxDiffSize = KarandashSize.ofKb(64)),
        )
        val findings = SecurityValidator(config).validate(
            createTestMergeRequest(
                changes = listOf(FileChange(path = "a.txt", changeType = ChangeType.MODIFIED, additions = 1, diffContent = diff))
            )
        )
        assertTrue(findings.any { it.message.contains("too large to scan") }, findings.toString())
    }

    @Test
    fun `sensitive file check runs independent of scanForSecrets`() {
        val config = SecurityConfig(scanForSecrets = false, sensitiveFiles = listOf("**/.env"))
        val findings = SecurityValidator(config).validate(
            createTestMergeRequest(changes = listOf(FileChange(path = "app/.env", changeType = ChangeType.ADDED, additions = 1)))
        )
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("Sensitive file"))
    }

    @Test
    fun `large diff finding has its own suggestion`() {
        val config = SecurityConfig(secretPatterns = secretPattern, sensitiveFiles = emptyList())
        val findings = SecurityValidator(config).validate(
            createTestMergeRequest(
                changes = listOf(
                    FileChange(
                        path = "big.txt",
                        changeType = ChangeType.MODIFIED,
                        additions = 1,
                        diffContent = "x".repeat(2_000_000),
                    )
                )
            )
        )
        val suggestion = findings.single().suggestion.orEmpty()
        assertTrue(suggestion.contains("maxDiffSize"), suggestion)
        assertTrue(!suggestion.contains("Move secrets"), suggestion)
    }
}
