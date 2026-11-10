package io.karandash.plugins.ai.prompts

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PromptResolverTest {
    private fun makeMr() =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test PR",
            description = "Description",
            sourceBranch = "feature/test",
            targetBranch = "main",
            author = User("dev", "Developer"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = listOf(
                Commit("sha1", "Test commit", CommitAuthor("Dev", "d@t.com"), "2024-01-01T00:00:00Z"),
            ),
            changes = listOf(
                FileChange("src/Main.kt", ChangeType.MODIFIED, additions = 1, deletions = 0),
            ),
        )

    @Test
    fun `resolve with Builtin returns builtin prompt with injection protection`() =
        runTest {
            val resolver = PromptResolver()
            val result = resolver.resolve(
                ruleId = "risk-scoring",
                source = PromptSource.Builtin,
                builtinPrompt = "You are a risk scorer.",
                mr = makeMr(),
            )

            assertTrue(result.contains("You are a risk scorer."))
            assertTrue(result.contains(PromptResolver.INJECTION_PROTECTION))
        }

    @Test
    fun `resolve with Inline uses inline text`() =
        runTest {
            val resolver = PromptResolver()
            val result = resolver.resolve(
                ruleId = "risk-scoring",
                source = PromptSource.Inline("Custom inline prompt for {{mr.title}}"),
                builtinPrompt = "DEFAULT",
                mr = makeMr(),
            )

            assertTrue(result.contains("Custom inline prompt for <untrusted-mr-title/>"))
            assertTrue(!result.contains("DEFAULT"))
        }

    @Test
    fun `resolve with extra instructions appends them`() =
        runTest {
            val resolver = PromptResolver()
            val result = resolver.resolve(
                ruleId = "risk-scoring",
                source = PromptSource.Builtin,
                builtinPrompt = "Base prompt.",
                mr = makeMr(),
                extraInstructions = "Focus on security.",
            )

            assertTrue(result.contains("Base prompt."))
            assertTrue(result.contains("Additional instructions:"))
            assertTrue(result.contains("Focus on security."))
        }

    @Test
    fun `resolve applies template variables from MR`() =
        runTest {
            val resolver = PromptResolver()
            val result = resolver.resolveSplit(
                ruleId = "risk-scoring",
                source = PromptSource.Inline("Branch: {{mr.sourceBranch}}"),
                builtinPrompt = "DEFAULT",
                mr = makeMr(),
            )

            assertTrue(result.systemPrompt.contains("Branch: <untrusted-mr-sourceBranch/>"))
            assertEquals("<untrusted-mr-sourceBranch>feature/test</untrusted-mr-sourceBranch>", result.mrData)
        }

    @Test
    fun `resolve with null MR skips template rendering`() =
        runTest {
            val resolver = PromptResolver()
            val result = resolver.resolve(
                ruleId = "risk-scoring",
                source = PromptSource.Inline("Branch: {{mr.sourceBranch}}"),
                builtinPrompt = "DEFAULT",
                mr = null,
            )

            assertTrue(result.contains("{{mr.sourceBranch}}"))
        }

    @Test
    fun `resolve with File source falls back to builtin`() =
        runTest {
            val resolver = PromptResolver()
            val result = resolver.resolve(
                ruleId = "risk-scoring",
                source = PromptSource.File("/nonexistent/path"),
                builtinPrompt = "Builtin fallback.",
                mr = makeMr(),
            )

            assertTrue(result.contains("Builtin fallback."))
        }

    @Test
    fun `resolve with Url source and no fetcher falls back to builtin`() =
        runTest {
            val resolver = PromptResolver(remoteFetcher = null)
            val result = resolver.resolve(
                ruleId = "risk-scoring",
                source = PromptSource.Url(url = "https://example.com/prompt.txt"),
                builtinPrompt = "Builtin fallback.",
                mr = makeMr(),
            )

            assertTrue(result.contains("Builtin fallback."))
        }

    @Test
    fun `resolve with custom context passes variables to template engine`() =
        runTest {
            val resolver = PromptResolver()
            val result = resolver.resolve(
                ruleId = "risk-scoring",
                source = PromptSource.Inline("Team: {{team_name}}"),
                builtinPrompt = "DEFAULT",
                mr = makeMr(),
                customContext = mapOf("team_name" to "platform"),
            )

            assertTrue(result.contains("Team: platform"))
        }

    @Test
    fun `resolvePair returns separate system and user prompts`() =
        runTest {
            val resolver = PromptResolver()
            val pair = resolver.resolvePair(
                ruleId = "review-comments",
                source = PromptSource.Pair(
                    system = PromptSource.Inline("You are a reviewer."),
                    user = PromptSource.Inline("Review this code."),
                ),
                builtinPrompt = "DEFAULT",
                mr = makeMr(),
            )

            assertTrue(pair.systemPrompt.contains("You are a reviewer."))
            assertNotNull(pair.userPrompt) { userPrompt ->
                assertTrue(userPrompt.contains("Review this code."))
            }
        }

    @Test
    fun `resolvePair with Builtin user returns null userPrompt`() =
        runTest {
            val resolver = PromptResolver()
            val pair = resolver.resolvePair(
                ruleId = "review-comments",
                source = PromptSource.Pair(
                    system = PromptSource.Inline("System prompt."),
                    user = PromptSource.Builtin,
                ),
                builtinPrompt = "DEFAULT",
                mr = makeMr(),
            )

            assertTrue(pair.systemPrompt.contains("System prompt."))
            assertTrue(pair.userPrompt == null)
        }
}
