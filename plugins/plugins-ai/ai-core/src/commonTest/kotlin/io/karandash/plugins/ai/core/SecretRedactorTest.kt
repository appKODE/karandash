package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretRedactorTest {
    // region redactText - pattern coverage

    @Test
    fun `redacts Anthropic API key`() {
        val key = "sk-ant-api03-" + "X".repeat(89)
        val redacted = SecretRedactor.redactText("key=$key")
        assertFalse(redacted.contains(key))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts OpenAI API key`() {
        val key = "sk-" + "B".repeat(48)
        val redacted = SecretRedactor.redactText("openai_key=$key")
        assertFalse(redacted.contains(key))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts password assignment with colon`() {
        val redacted = SecretRedactor.redactText("password: mysupersecret123")
        assertFalse(redacted.contains("mysupersecret123"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts secret assignment with equals`() {
        val redacted = SecretRedactor.redactText("secret=topsecretvalue")
        assertFalse(redacted.contains("topsecretvalue"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts token assignment`() {
        val redacted = SecretRedactor.redactText("token: abcdef1234567890")
        assertFalse(redacted.contains("abcdef1234567890"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts api_key assignment`() {
        val redacted = SecretRedactor.redactText("api_key=MYAPIKEY12345")
        assertFalse(redacted.contains("MYAPIKEY12345"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts private_key assignment`() {
        val redacted = SecretRedactor.redactText("private_key=myprivatekeyvalue")
        assertFalse(redacted.contains("myprivatekeyvalue"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts Bearer token`() {
        val token = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig"
        val redacted = SecretRedactor.redactText("Authorization: Bearer $token")
        assertFalse(redacted.contains(token))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts PEM PRIVATE KEY header`() {
        val redacted = SecretRedactor.redactText("-----BEGIN PRIVATE KEY-----\nMIIEvAIBADANBg...")
        assertFalse(redacted.contains("-----BEGIN PRIVATE KEY-----"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts RSA PRIVATE KEY header`() {
        val redacted = SecretRedactor.redactText("-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQ...")
        assertFalse(redacted.contains("-----BEGIN RSA PRIVATE KEY-----"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts GitHub PAT`() {
        val token = "ghp_" + "A".repeat(36)
        val redacted = SecretRedactor.redactText("GITHUB_TOKEN=$token")
        assertFalse(redacted.contains(token))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts GitLab PAT`() {
        val token = "glpat-" + "B".repeat(20)
        val redacted = SecretRedactor.redactText("CI_JOB_TOKEN=$token")
        assertFalse(redacted.contains(token))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts AWS access key`() {
        val key = "AKIA" + "A".repeat(16)
        val redacted = SecretRedactor.redactText("AWS_ACCESS_KEY_ID=$key")
        assertFalse(redacted.contains(key))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts JDBC connection string with password`() {
        val jdbc = "jdbc:mysql://host:3306/mydb?user=admin&password=p4ssw0rd!"
        val redacted = SecretRedactor.redactText("DB_URL=$jdbc")
        assertFalse(redacted.contains("p4ssw0rd!"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `clean text without secrets passes through unchanged`() {
        val text = "Fix the login timeout bug. Reduce session expiry from 30 to 10 minutes."
        assertEquals(text, SecretRedactor.redactText(text))
    }

    @Test
    fun `redacts multiple secrets in a single text`() {
        val anthropicKey = "sk-ant-api03-" + "A".repeat(89)
        val openAiKey = "sk-" + "B".repeat(48)
        val text = "Anthropic: $anthropicKey\nOpenAI: $openAiKey"
        val redacted = SecretRedactor.redactText(text)
        assertFalse(redacted.contains(anthropicKey))
        assertFalse(redacted.contains(openAiKey))
        assertEquals(2, redacted.split("[REDACTED]").size - 1)
    }

    // region redact(MergeRequest)

    private fun makeMr(
        description: String? = null,
        diffContent: String? = null,
        commitMessage: String = "Normal commit"
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = "Test PR",
        description = description,
        sourceBranch = "feature/test",
        targetBranch = "main",
        author = User("u1", "dev", "Developer"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = listOf(
            Commit("sha1", commitMessage, CommitAuthor("Dev", "dev@test.com"), "2024-01-01T00:00:00Z")
        ),
        changes = listOf(
            FileChange("src/Config.kt", ChangeType.MODIFIED, additions = 3, deletions = 1, diffContent = diffContent)
        )
    )

    @Test
    fun `redact MergeRequest sanitizes secret in description`() {
        val key = "AKIA" + "Z".repeat(16)
        val mr = makeMr(description = "AWS_ACCESS_KEY_ID=$key")
        val redacted = SecretRedactor.redact(mr)
        assertFalse(redacted.description!!.contains(key))
        assertTrue(redacted.description!!.contains("[REDACTED]"))
    }

    @Test
    fun `redact MergeRequest sanitizes secret in diffContent`() {
        val token = "ghp_" + "C".repeat(36)
        val mr = makeMr(diffContent = "+GITHUB_TOKEN=$token")
        val redacted = SecretRedactor.redact(mr)
        val diff = redacted.changes.first().diffContent!!
        assertFalse(diff.contains(token))
        assertTrue(diff.contains("[REDACTED]"))
    }

    @Test
    fun `redact MergeRequest sanitizes secret in commit message`() {
        val pat = "glpat-" + "D".repeat(20)
        val mr = makeMr(commitMessage = "Add token=$pat for CI auth")
        val redacted = SecretRedactor.redact(mr)
        val message = redacted.commits.first().message
        assertFalse(message.contains(pat))
        assertTrue(message.contains("[REDACTED]"))
    }

    @Test
    fun `redact MergeRequest preserves non-sensitive fields`() {
        val mr = makeMr(description = "Normal description with no secrets")
        val redacted = SecretRedactor.redact(mr)
        assertEquals(mr.id, redacted.id)
        assertEquals(mr.title, redacted.title)
        assertEquals(mr.sourceBranch, redacted.sourceBranch)
        assertEquals(mr.targetBranch, redacted.targetBranch)
        assertEquals(mr.author, redacted.author)
        assertEquals(mr.state, redacted.state)
        assertEquals(mr.labels, redacted.labels)
        assertEquals("Normal description with no secrets", redacted.description)
    }

    @Test
    fun `redact MergeRequest handles null description`() {
        val mr = makeMr(description = null)
        assertNull(SecretRedactor.redact(mr).description)
    }

    @Test
    fun `redact MergeRequest handles null diffContent`() {
        val mr = makeMr(diffContent = null)
        assertNull(
            SecretRedactor
                .redact(mr)
                .changes
                .first()
                .diffContent
        )
    }

    // ── Bug 5: title, sourceBranch, targetBranch redaction ───────────────

    @Test
    fun `redact MergeRequest sanitizes secret in title`() {
        val token = "ghp_" + "T".repeat(36)
        val mr = makeMr().copy(title = "Fix GITHUB_TOKEN=$token leak")
        val redacted = SecretRedactor.redact(mr)
        assertFalse(redacted.title.contains(token))
        assertTrue(redacted.title.contains("[REDACTED]"))
    }

    @Test
    fun `redact MergeRequest sanitizes secret in sourceBranch`() {
        val key = "AKIA" + "S".repeat(16)
        val mr = makeMr().copy(sourceBranch = "feature/aws-key-$key")
        val redacted = SecretRedactor.redact(mr)
        assertFalse(redacted.sourceBranch.contains(key))
        assertTrue(redacted.sourceBranch.contains("[REDACTED]"))
    }

    @Test
    fun `redact MergeRequest sanitizes secret in targetBranch`() {
        val pat = "glpat-" + "B".repeat(20)
        val mr = makeMr().copy(targetBranch = "release/token-$pat")
        val redacted = SecretRedactor.redact(mr)
        assertFalse(redacted.targetBranch.contains(pat))
        assertTrue(redacted.targetBranch.contains("[REDACTED]"))
    }

    @Test
    fun `redact MergeRequest processes title sourceBranch targetBranch when clean`() {
        val mr = makeMr()
        val redacted = SecretRedactor.redact(mr)
        assertEquals(mr.title, redacted.title)
        assertEquals(mr.sourceBranch, redacted.sourceBranch)
        assertEquals(mr.targetBranch, redacted.targetBranch)
    }

    // ── newContent and author email redaction ─────────────────────────────

    @Test
    fun `redact MergeRequest sanitizes secret in newContent`() {
        val token = "ghp_" + "N".repeat(36)
        val mr = makeMr().copy(
            changes = listOf(
                FileChange(
                    "src/Config.kt",
                    ChangeType.MODIFIED,
                    additions = 1,
                    deletions = 0,
                    newContent = "val token = \"$token\""
                )
            )
        )
        val redacted = SecretRedactor.redact(mr)
        val content = redacted.changes.first().newContent!!
        assertFalse(content.contains(token))
        assertTrue(content.contains("[REDACTED]"))
    }

    @Test
    fun `redact MergeRequest handles null newContent`() {
        val mr = makeMr()
        assertNull(
            SecretRedactor
                .redact(mr)
                .changes
                .first()
                .newContent
        )
    }

    @Test
    fun `redact MergeRequest sanitizes secret in author email`() {
        val key = "AKIA" + "E".repeat(16)
        val mr = makeMr().copy(author = User("u1", "dev", "token=$key"))
        val redacted = SecretRedactor.redact(mr)
        assertFalse(redacted.author.email!!.contains(key))
        assertTrue(redacted.author.email!!.contains("[REDACTED]"))
    }

    @Test
    fun `redact MergeRequest preserves clean author email`() {
        val mr = makeMr().copy(author = User("u1", "dev", "dev@example.com"))
        val redacted = SecretRedactor.redact(mr)
        assertEquals("dev@example.com", redacted.author.email)
    }

    @Test
    fun `redact MergeRequest handles null author email`() {
        val mr = makeMr().copy(author = User("u1", "dev", null))
        val redacted = SecretRedactor.redact(mr)
        assertNull(redacted.author.email)
    }

    @Test
    fun `a sensitive file keeps its path but loses its diff and content`() {
        val mr = makeMr().copy(
            changes = listOf(
                FileChange(
                    "config/.env",
                    ChangeType.MODIFIED,
                    diffContent = "+DB_HOST=internal.example",
                    newContent = "DB_HOST=internal.example"
                ),
                FileChange("src/App.kt", ChangeType.MODIFIED, diffContent = "+fun main() = Unit"),
            ),
        )

        val (env, app) = SecretRedactor.redact(mr).changes

        assertEquals("config/.env", env.path)
        assertFalse(env.diffContent.orEmpty().contains("internal.example"), env.diffContent)
        assertNull(env.newContent)
        assertEquals("+fun main() = Unit", app.diffContent)
    }
}
