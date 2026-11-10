package io.karandash.validators

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.security.TextRedactor
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityValidatorTest {
    @Test
    fun `a secret in an added line is reported with the short message`() {
        val validator = SecurityValidator(SecurityConfig(secretPatterns = listOf("TOKEN-\\d+"), sensitiveFiles = emptyList()))
        val diff = "@@ -1 +1,2 @@\n ctx\n+key TOKEN-1234"
        val change = FileChange(path = "src/Login.kt", changeType = ChangeType.MODIFIED, diffContent = diff)

        val finding = validator.validate(createTestMergeRequest(changes = listOf(change))).single()

        assertEquals("Potential secret in an added line", finding.message)
    }

    @Test
    fun `every credential format the AI redactor hides is a security finding by default`() {
        val samples = listOf(
            "-----BEGIN RSA PRIVATE KEY-----",
            "ghp_" + "a".repeat(36),
            "glpat-" + "b".repeat(20),
            "sk-" + "c".repeat(48),
            "sk-ant-" + "d".repeat(95),
            "AKIA" + "E".repeat(16),
            "jdbc:postgresql://db/app?user=u&password=hunter22",
        )
        assertEquals(TextRedactor.CREDENTIAL_FORMATS.size, samples.size, "one sample per format")
        val validator = SecurityValidator(SecurityConfig(sensitiveFiles = emptyList()))
        for (sample in samples) {
            val mr = createTestMergeRequest(commits = listOf(createCommit(message = "add config $sample")))
            assertTrue(validator.validate(mr).isNotEmpty(), "no finding for '$sample'")
        }
    }

    @Test
    fun testNoSecretsDetected() {
        val config = SecurityConfig(
            scanForSecrets = true,
            secretPatterns = SecurityConfig.defaultSecretPatterns,
            sensitiveFiles = emptyList()
        )
        val validator = SecurityValidator(config)

        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit(message = "Add new feature")
            ),
            title = "Add user authentication",
            description = "This PR adds authentication logic"
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Expected no findings for clean MR")
    }

    @Test
    fun testSecretInCommitMessage() {
        val config = SecurityConfig(
            scanForSecrets = true,
            secretPatterns = listOf("(?i)api[_-]?key\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-]{20,}['\"]?"),
            sensitiveFiles = emptyList()
        )
        val validator = SecurityValidator(config)

        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit(message = "Fix bug\napi_key = abcdef1234567890abcdef")
            )
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("security", findings[0].rule)
        assertTrue(findings[0].message.contains("commit message"))
    }

    @Test
    fun testSecretInMRTitle() {
        val config = SecurityConfig(
            scanForSecrets = true,
            secretPatterns = listOf("(?i)password\\s*[:=]\\s*['\"]?[^\\s'\"]{8,}['\"]?"),
            sensitiveFiles = emptyList()
        )
        val validator = SecurityValidator(config)

        val mr = createTestMergeRequest(
            title = "Update config password=mysecret123"
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("security", findings[0].rule)
        assertTrue(findings[0].message.contains("MR title"))
    }

    @Test
    fun testSecretInMRDescription() {
        val config = SecurityConfig(
            scanForSecrets = true,
            secretPatterns = listOf("(?i)access[_-]?token\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-]{20,}['\"]?"),
            sensitiveFiles = emptyList()
        )
        val validator = SecurityValidator(config)

        val mr = createTestMergeRequest(
            description = "Fixed the issue\naccess_token = test-github-token-xxx" // Test token, not real
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("security", findings[0].rule)
        assertTrue(findings[0].message.contains("MR description"))
    }

    @Test
    fun testSensitiveFileChange() {
        val config = SecurityConfig(
            scanForSecrets = false,
            secretPatterns = emptyList(),
            sensitiveFiles = listOf("**/.env", "**/*.pem")
        )
        val validator = SecurityValidator(config)

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange(path = "config/.env", changeType = ChangeType.MODIFIED),
                FileChange(path = "src/main.kt", changeType = ChangeType.MODIFIED)
            )
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.WARNING, findings[0].severity)
        assertEquals("security", findings[0].rule)
        assertTrue(findings[0].message.contains("config/.env"))
    }

    @Test
    fun testMultipleSensitiveFiles() {
        val config = SecurityConfig(
            scanForSecrets = false,
            secretPatterns = emptyList(),
            sensitiveFiles = listOf("**/*.key", "**/*.pem")
        )
        val validator = SecurityValidator(config)

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange(path = "certs/server.key", changeType = ChangeType.ADDED),
                FileChange(path = "certs/ca.pem", changeType = ChangeType.MODIFIED),
                FileChange(path = "src/main.kt", changeType = ChangeType.MODIFIED)
            )
        )

        val findings = validator.validate(mr)
        assertEquals(2, findings.size)
        assertTrue(findings.all { it.severity == Severity.WARNING })
        assertTrue(findings.all { it.rule == "security" })
    }

    @Test
    fun testGlobPatternMatching() {
        val config = SecurityConfig(
            scanForSecrets = false,
            secretPatterns = emptyList(),
            sensitiveFiles = listOf("**/secrets.yml", "config/credentials/*")
        )
        val validator = SecurityValidator(config)

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange(path = "app/config/secrets.yml", changeType = ChangeType.MODIFIED),
                FileChange(path = "config/credentials/prod.yml", changeType = ChangeType.MODIFIED),
                FileChange(path = "config/settings.yml", changeType = ChangeType.MODIFIED)
            )
        )

        val findings = validator.validate(mr)
        assertEquals(2, findings.size)
    }

    @Test
    fun testScanningDisabled() {
        val config = SecurityConfig(
            scanForSecrets = false,
            secretPatterns = listOf("(?i)api_key"),
            sensitiveFiles = emptyList()
        )
        val validator = SecurityValidator(config)

        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit(message = "api_key = secret123")
            )
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Expected no findings when scanning is disabled")
    }

    @Test
    fun testMultipleSecretsDetected() {
        val config = SecurityConfig(
            scanForSecrets = true,
            secretPatterns = listOf(
                "(?i)api_key\\s*[:=]\\s*['\"]?[a-zA-Z0-9]{10,}['\"]?",
                "(?i)password\\s*[:=]\\s*['\"]?[^\\s'\"]{8,}['\"]?"
            ),
            sensitiveFiles = emptyList()
        )
        val validator = SecurityValidator(config)

        val mr = createTestMergeRequest(
            title = "Update config api_key = abc123456789",
            description = "Also changed password = mypassword123"
        )

        val findings = validator.validate(mr)
        assertEquals(2, findings.size)
        assertTrue(findings.all { it.severity == Severity.ERROR })
        assertTrue(findings.all { it.rule == "security" })
    }

    @Test
    fun testInvalidRegexInSecretPatternsReturnsError() {
        val config = SecurityConfig(
            scanForSecrets = true,
            secretPatterns = listOf("[invalid"),
            sensitiveFiles = emptyList()
        )
        val validator = SecurityValidator(config)

        val mr = createTestMergeRequest(
            title = "Test MR"
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("security", findings[0].rule)
        assertTrue(findings[0].message.contains("Invalid regex pattern"))
    }

    @Test
    fun testMultipleInvalidRegexPatternsReturnsFirstError() {
        val config = SecurityConfig(
            scanForSecrets = true,
            secretPatterns = listOf("(?i)api_key", "[invalid(", "(?i)password"),
            sensitiveFiles = emptyList()
        )
        val validator = SecurityValidator(config)

        val mr = createTestMergeRequest(
            title = "Test MR"
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("Invalid regex pattern"))
    }

    @Test
    fun testSecretInDiffHasLineNumber() {
        val config = SecurityConfig(
            scanForSecrets = true,
            secretPatterns = listOf("(?i)api_key\\s*=\\s*['\"]?[a-zA-Z0-9]{10,}['\"]?"),
            sensitiveFiles = emptyList()
        )
        val validator = SecurityValidator(config)

        val diffContent = """
            diff --git a/config.kt b/config.kt
            --- a/config.kt
            +++ b/config.kt
            @@ -1,3 +1,5 @@
             object Config {
            +    val api_key = "abcdef1234567890"
            +    val name = "test"
                 val version = 1
             }
        """.trimIndent()

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange("config.kt", ChangeType.MODIFIED, diffContent = diffContent)
            )
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        val finding = findings[0]
        assertEquals(Severity.ERROR, finding.severity)
        val loc = finding.location as Location.FileRef
        assertEquals("config.kt", loc.path)
        assertEquals(2, loc.line)
    }

    @Test
    fun testSecretOnlyInRemovedLineNotFlagged() {
        val config = SecurityConfig(
            scanForSecrets = true,
            secretPatterns = listOf("(?i)api_key\\s*=\\s*['\"]?[a-zA-Z0-9]{10,}['\"]?"),
            sensitiveFiles = emptyList()
        )
        val validator = SecurityValidator(config)

        val diffContent = """
            diff --git a/config.kt b/config.kt
            --- a/config.kt
            +++ b/config.kt
            @@ -1,4 +1,3 @@
             object Config {
            -    val api_key = "abcdef1234567890"
                 val version = 1
             }
        """.trimIndent()

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange("config.kt", ChangeType.MODIFIED, diffContent = diffContent)
            )
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Removed lines should not trigger secret detection")
    }

    @Test
    fun testMultipleSecretsInDiffHaveDistinctLineNumbers() {
        val config = SecurityConfig(
            scanForSecrets = true,
            secretPatterns = listOf(
                "(?i)api_key\\s*=\\s*['\"]?[a-zA-Z0-9]{10,}['\"]?",
                "(?i)password\\s*=\\s*['\"]?[^\\s'\"]{8,}['\"]?"
            ),
            sensitiveFiles = emptyList()
        )
        val validator = SecurityValidator(config)

        val diffContent = """
            diff --git a/config.kt b/config.kt
            --- a/config.kt
            +++ b/config.kt
            @@ -1,2 +1,4 @@
             object Config {
            +    val api_key = "abcdef1234567890"
            +    val password = "supersecret123"
             }
        """.trimIndent()

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange("config.kt", ChangeType.MODIFIED, diffContent = diffContent)
            )
        )

        val findings = validator.validate(mr)
        assertEquals(2, findings.size)
        val lines = findings.mapNotNull { (it.location as? Location.FileRef)?.line }.sorted()
        assertEquals(listOf(2, 3), lines)
    }

    @Test
    fun testSecretInDiffWithNullDiffContentSkipped() {
        val config = SecurityConfig(
            scanForSecrets = true,
            secretPatterns = listOf("(?i)api_key"),
            sensitiveFiles = emptyList()
        )
        val validator = SecurityValidator(config)

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange("config.kt", ChangeType.MODIFIED, diffContent = null)
            )
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testSensitiveFileChangeHasNoLineNumber() {
        val config = SecurityConfig(
            scanForSecrets = false,
            secretPatterns = emptyList(),
            sensitiveFiles = listOf("**/.env")
        )
        val validator = SecurityValidator(config)

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange(path = "config/.env", changeType = ChangeType.MODIFIED)
            )
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        val loc = findings[0].location as Location.FileRef
        assertEquals("config/.env", loc.path)
        assertNull(loc.line)
    }

    // ── Bug 10: oversized diff skipped with warning ────────────────────

    @Test
    fun testOversizedDiffProducesWarningInsteadOfScanning() {
        val config = SecurityConfig(
            scanForSecrets = true,
            secretPatterns = listOf("password\\s*=\\s*\\S+"),
            sensitiveFiles = emptyList()
        )
        val validator = SecurityValidator(config)

        val largeDiff = "password = secret123\n".repeat(100_000)
        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange(
                    path = "big-file.txt",
                    changeType = ChangeType.MODIFIED,
                    additions = 100_000,
                    diffContent = largeDiff
                )
            )
        )

        val findings = validator.validate(mr)
        val warnings = findings.filter { it.severity == Severity.WARNING }
        assertTrue(warnings.any { it.message.contains("too large to scan") }, "Expected oversized diff warning")
    }

    private fun createCommit(
        sha: String = "abcdef1234567890",
        message: String = "Test commit"
    ): Commit =
        Commit(
            sha = sha,
            message = message,
            author = CommitAuthor(name = "Test User", email = "test@example.com"),
            timestamp = "2024-01-01T12:00:00Z"
        )
}
