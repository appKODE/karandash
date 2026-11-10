package io.karandash.plugins.ai.pipeline

import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.api.finding.ReviewThreadPosition
import io.karandash.api.run.MergeRequestThreads
import io.karandash.logger.NoOpLogger
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AiDuplicatesTest {
    private val processor = AiFindingPostProcessor(NoOpLogger)
    private val text = "The retry loop never backs off, so a failing server gets hammered."

    private fun ai(
        rule: String,
        message: String = text,
    ) = Finding(
        severity = Severity.WARNING,
        message = message,
        rule = rule,
        location = Location.FileRef("src/A.kt", 10),
        metadata = mapOf(AiFindingMetadataKeys.AI_MODEL to "m"),
    )

    private fun threads(listed: () -> List<ReviewThread>?) =
        object : MergeRequestThreads {
            override suspend fun list() = listed()

            override suspend fun isMember(username: String) = false

            override suspend fun resolve(
                threadId: String,
                note: String,
            ) = Result.success(Unit)

            override suspend fun reply(
                threadId: String,
                body: String,
            ) = Result.success(Unit)
        }

    private val open = ReviewThread(
        id = "t",
        notes = listOf(ReviewNote(id = "n", body = "retry loop never backs off, hammering the failing server")),
        position = ReviewThreadPosition(newPath = "src/A.kt", newLine = 12),
    )

    @Test
    fun `finding of an open discussion is dropped with its code suggestion`() =
        runTest {
            val comment = ai("review-comments")
            val suggestion = ai("code-suggestions", "Use exponential backoff between attempts.")
            val result = processor.applyDuplicates(listOf(comment, suggestion), null, threads { listOf(open) })
            assertEquals(emptyList(), result)
        }

    @Test
    fun `dedupOpenThreads false keeps the finding`() =
        runTest {
            val comment = ai("review-comments")
            val config = Json.parseToJsonElement("""{"ai": {"filter": {"dedupOpenThreads": false}}}""")
            assertEquals(listOf(comment), processor.applyDuplicates(listOf(comment), config, threads { listOf(open) }))
        }

    @Test
    fun `unreadable threads keep every finding`() =
        runTest {
            val comment = ai("review-comments")
            assertEquals(listOf(comment), processor.applyDuplicates(listOf(comment), null, threads { error("api down") }))
            assertEquals(listOf(comment), processor.applyDuplicates(listOf(comment), null, threads { null }))
            assertEquals(listOf(comment), processor.applyDuplicates(listOf(comment), null, null))
        }
}
