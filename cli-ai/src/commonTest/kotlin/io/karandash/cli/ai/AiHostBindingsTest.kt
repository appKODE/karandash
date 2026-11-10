package io.karandash.cli.ai

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The AI-tier calls `:cli` makes through `:cli-ai` keep their behavior. */
class AiHostBindingsTest {
    private fun mr(
        id: String,
        vararg paths: String,
    ) = MergeRequest(
        id = id,
        iid = id.length,
        title = "MR $id",
        description = null,
        sourceBranch = "feature-$id",
        targetBranch = "main",
        author = User("dev", "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = paths.map { FileChange(it, ChangeType.MODIFIED) },
    )

    @Test
    fun `dependency report lists files changed by both MRs`() {
        val report = AiHostBindings.dependencyReport(mr("1", "a.kt", "b.kt"), listOf(mr("2", "b.kt")))
        assertTrue("### File Conflicts" in report, report)
        assertTrue("MR 2 ('MR 2'): b.kt" in report, report)
    }

    @Test
    fun `ai chain describes the main provider and reports a missing ai block`() {
        assertEquals(listOf("ai chain: not configured"), AiHostBindings.describeAiChain(null))
        val lines = AiHostBindings.describeAiChain(JsonObject(mapOf("provider" to JsonPrimitive("openai"))))
        assertTrue(lines.first().startsWith("ai chain: main=openai"), lines.first())
    }
}
