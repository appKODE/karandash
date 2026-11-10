package io.karandash.validators

import io.karandash.config.Config
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RulesConfig
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestEngine
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.testfixtures.toJsonElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommitMessagesValidationEngineTest {
    @Test
    fun `validateCommits with empty commit list produces no findings`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "commitMessages" to CommitMessagesConfig(format = "conventional").toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(commits = emptyList())
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            assertTrue(result.findings.isEmpty())
        }

    @Test
    fun `validateCommits with empty commit message`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "commitMessages" to CommitMessagesConfig(format = "conventional").toJsonElement()
                    )
                )
            )
            val commit = Commit(
                sha = "abc123",
                message = "", // Empty message
                author = CommitAuthor("user", "user@example.com"),
                timestamp = "2024-01-01T00:00:00Z"
            )
            val mr = createTestMergeRequest(commits = listOf(commit))
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            assertEquals(1, result.findings.size)
            assertEquals(Severity.ERROR, result.findings[0].severity)
            assertEquals("commitMessages", result.findings[0].rule)
            assertTrue(result.findings[0].message.contains("does not follow conventional format"))
        }

    @Test
    fun `validateCommits with valid conventional commit format passes`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "commitMessages" to CommitMessagesConfig(format = "conventional").toJsonElement()
                    )
                )
            )
            val commit = Commit(
                sha = "abc123",
                message = "feat: add new feature",
                author = CommitAuthor("user", "user@example.com"),
                timestamp = "2024-01-01T00:00:00Z"
            )
            val mr = createTestMergeRequest(commits = listOf(commit))
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            assertTrue(result.findings.isEmpty())
        }

    @Test
    fun `validateCommits with invalid conventional commit format fails`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "commitMessages" to CommitMessagesConfig(format = "conventional").toJsonElement()
                    )
                )
            )
            val commit = Commit(
                sha = "abc123",
                message = "invalid commit message",
                author = CommitAuthor("user", "user@example.com"),
                timestamp = "2024-01-01T00:00:00Z"
            )
            val mr = createTestMergeRequest(commits = listOf(commit))
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            assertEquals(1, result.findings.size)
            assertEquals(Severity.ERROR, result.findings[0].severity)
            assertTrue(result.findings[0].message.contains("does not follow conventional format"))
        }

    @Test
    fun `validateCommits with conventional commit missing colon fails`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "commitMessages" to CommitMessagesConfig(format = "conventional").toJsonElement()
                    )
                )
            )
            val commit = Commit(
                sha = "abc123",
                message = "feat add feature", // Missing colon
                author = CommitAuthor("user", "user@example.com"),
                timestamp = "2024-01-01T00:00:00Z"
            )
            val mr = createTestMergeRequest(commits = listOf(commit))
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            assertEquals(1, result.findings.size)
            assertEquals(Severity.ERROR, result.findings[0].severity)
        }

    @Test
    fun `validateCommits with breaking change marker passes`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "commitMessages" to CommitMessagesConfig(format = "conventional").toJsonElement()
                    )
                )
            )
            val commit = Commit(
                sha = "abc123",
                message = "feat!: breaking change",
                author = CommitAuthor("user", "user@example.com"),
                timestamp = "2024-01-01T00:00:00Z"
            )
            val mr = createTestMergeRequest(commits = listOf(commit))
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            assertTrue(result.findings.isEmpty())
        }

    @Test
    fun `validateCommits with scope passes`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "commitMessages" to CommitMessagesConfig(format = "conventional").toJsonElement()
                    )
                )
            )
            val commit = Commit(
                sha = "abc123",
                message = "fix(scope): bug fix",
                author = CommitAuthor("user", "user@example.com"),
                timestamp = "2024-01-01T00:00:00Z"
            )
            val mr = createTestMergeRequest(commits = listOf(commit))
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            assertTrue(result.findings.isEmpty())
        }

    @Test
    fun `validateCommits with scope containing special characters passes`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "commitMessages" to CommitMessagesConfig(format = "conventional").toJsonElement()
                    )
                )
            )
            val commit = Commit(
                sha = "abc123",
                message = "feat(my-scope_v2): test",
                author = CommitAuthor("user", "user@example.com"),
                timestamp = "2024-01-01T00:00:00Z"
            )
            val mr = createTestMergeRequest(commits = listOf(commit))
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            assertTrue(result.findings.isEmpty())
        }

    @Test
    fun `validateCommits with maxLength exactly at boundary passes`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "commitMessages" to CommitMessagesConfig(maxLength = 50).toJsonElement()
                    )
                )
            )
            val commit = Commit(
                sha = "abc123",
                message = "X".repeat(50), // Exactly 50 chars
                author = CommitAuthor("user", "user@example.com"),
                timestamp = "2024-01-01T00:00:00Z"
            )
            val mr = createTestMergeRequest(commits = listOf(commit))
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            val lengthFindings = result.findings.filter { it.message.contains("chars (limit") }
            assertTrue(lengthFindings.isEmpty())
        }

    @Test
    fun `validateCommits with first line exceeding maxLength produces warning`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "commitMessages" to CommitMessagesConfig(maxLength = 50).toJsonElement()
                    )
                )
            )
            val commit = Commit(
                sha = "abc123",
                message = "X".repeat(51), // 51 chars
                author = CommitAuthor("user", "user@example.com"),
                timestamp = "2024-01-01T00:00:00Z"
            )
            val mr = createTestMergeRequest(commits = listOf(commit))
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            val lengthFindings = result.findings.filter { it.message.contains("chars (limit") }
            assertEquals(1, lengthFindings.size)
            assertEquals(Severity.WARNING, lengthFindings[0].severity)
        }

    @Test
    fun `validateCommits with multiline message only validates first line length`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "commitMessages" to CommitMessagesConfig(maxLength = 50).toJsonElement()
                    )
                )
            )
            val commit = Commit(
                sha = "abc123",
                message = "Short first line\n" + "X".repeat(100), // Second line is very long
                author = CommitAuthor("user", "user@example.com"),
                timestamp = "2024-01-01T00:00:00Z"
            )
            val mr = createTestMergeRequest(commits = listOf(commit))
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            // Should not flag because only first line is checked
            val lengthFindings = result.findings.filter { it.message.contains("chars (limit") }
            assertTrue(lengthFindings.isEmpty())
        }

    @Test
    fun `pattern mismatch names the failing commits when the config sets an explanation`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("commitMessages" to CommitMessagesConfig(pattern = "^\\[MAPS-\\d+\\].*").toJsonElement()),
                    ruleMeta = mapOf("commitMessages" to RuleExecutionMeta(explanation = "Use [MAPS-n] subjects.")),
                ),
            )
            val commits = listOf("aaaaaaa1" to "wip", "bbbbbbb2" to "[MAPS-1] ok", "ccccccc3" to "fix").map { (sha, message) ->
                Commit(sha = sha, message = message, author = CommitAuthor("user", "user@example.com"), timestamp = "2024-01-01T00:00:00Z")
            }

            val finding = createTestEngine(config).validate(createTestMergeRequest(commits = commits)).findings.single()

            assertEquals("Use [MAPS-n] subjects.", finding.explanation)
            assertEquals("Commit message does not match required pattern: aaaaaaa, ccccccc", finding.message)
        }
}
