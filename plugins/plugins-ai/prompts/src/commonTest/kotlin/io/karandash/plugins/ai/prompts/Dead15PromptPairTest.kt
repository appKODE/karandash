package io.karandash.plugins.ai.prompts

import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * the rule path ([PromptResolver.resolveSplit]) resolves an `ai.prompts.<rule>` `system:`/`user:` pair through
 * [PromptResolver.resolvePair], so the `user:` side reaches the user message instead of being appended to the system prompt.
 */
class Dead15PromptPairTest {
    private val mr =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Add cache",
            description = null,
            sourceBranch = "feature/x",
            targetBranch = "main",
            author = User("dev", "Dev Eloper"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
        )

    private val title = "<untrusted-mr-title>Add cache</untrusted-mr-title>"

    @Test
    fun `a system and user prompt pair keeps the user side out of the system prompt`() =
        runTest {
            val source = PromptSource.fromJson(
                Json.parseToJsonElement("""{"system": "SYS {{mr.title}}", "user": "Focus on {{mr.title}}"}""")
            )

            val resolved = PromptResolver().resolveSplit("review-comments", source, "BUILTIN", mr, extraInstructions = "EXTRA")

            assertEquals(
                "SYS <untrusted-mr-title/>\n\n${PromptResolver.INJECTION_PROTECTION}\n\nAdditional instructions:\nEXTRA",
                resolved.systemPrompt,
            )
            assertEquals("$title\n\nFocus on $title", resolved.mrData)
        }

    @Test
    fun `a system only pair resolves as before`() =
        runTest {
            val source = PromptSource.fromJson(Json.parseToJsonElement("""{"system": "SYS {{mr.title}}"}"""))

            val resolved = PromptResolver().resolveSplit("review-comments", source, "BUILTIN", mr)

            assertEquals("SYS <untrusted-mr-title/>\n\n${PromptResolver.INJECTION_PROTECTION}", resolved.systemPrompt)
            assertEquals(title, resolved.mrData)
        }
}
