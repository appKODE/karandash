package io.karandash.cli.pipeline

import io.karandash.models.Approval
import io.karandash.models.ApprovalMethod
import io.karandash.models.ApprovalSignal
import io.karandash.models.User
import io.karandash.sdk.NoOpLogger
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** ApprovalMethod COMMENT_KEYWORD and EMOJI_REACTION are populated from platform signals. */
class ApprovalSignalStageTest {
    private val stage = ApprovalSignalStage(NoOpLogger)
    private val mr = createTestMergeRequest(approvals = listOf(Approval(User("alice", "Alice"))))
    private val signals = listOf(
        ApprovalSignal(User("bob", "Bob"), ApprovalMethod.COMMENT_KEYWORD, "LGTM!"),
        ApprovalSignal(User("carol", "Carol"), ApprovalMethod.COMMENT_KEYWORD, "please fix the typo"),
        ApprovalSignal(User("dave", "Dave"), ApprovalMethod.EMOJI_REACTION, "thumbsup"),
        ApprovalSignal(User("alice", "Alice"), ApprovalMethod.COMMENT_KEYWORD, "lgtm"),
    )

    private fun config(vararg methods: String) = JsonObject(mapOf("methods" to JsonArray(methods.map { JsonPrimitive(it) })))

    @Test
    fun `matching comments and reactions become approvals with their method`() =
        runTest {
            val result = stage.apply(mr, config("CODE_REVIEW", "COMMENT_KEYWORD", "EMOJI_REACTION")) { signals }

            assertEquals(listOf("alice", "bob", "dave"), result.approvals.map { it.user.username })
            assertEquals(
                listOf(ApprovalMethod.CODE_REVIEW, ApprovalMethod.COMMENT_KEYWORD, ApprovalMethod.EMOJI_REACTION),
                result.approvals.map { it.method },
            )
        }

    @Test
    fun `the platform is not asked when only code review counts`() =
        runTest {
            val result = stage.apply(mr, config("CODE_REVIEW")) { error("must not be called") }

            assertEquals(mr, result)
        }

    @Test
    fun `a failing platform call leaves the approvals alone`() =
        runTest {
            val result = stage.apply(mr, config("COMMENT_KEYWORD")) { throw IllegalStateException("boom") }

            assertEquals(mr, result)
        }

    @Test
    fun `configured keywords replace the defaults`() =
        runTest {
            val cfg = JsonObject(
                mapOf(
                    "methods" to JsonArray(listOf(JsonPrimitive("COMMENT_KEYWORD"))),
                    "commentKeywords" to JsonArray(listOf(JsonPrimitive("ship it"))),
                ),
            )
            val shipIt = listOf(ApprovalSignal(User("bob", "Bob"), ApprovalMethod.COMMENT_KEYWORD, "Ship it"))

            assertEquals(listOf("alice", "bob"), stage.apply(mr, cfg) { shipIt }.approvals.map { it.user.username })
            assertEquals(listOf("alice"), stage.apply(mr, cfg) { signals }.approvals.map { it.user.username })
        }

    @Test
    fun `configured emojis replace the defaults`() =
        runTest {
            val cfg = JsonObject(
                mapOf(
                    "methods" to JsonArray(listOf(JsonPrimitive("EMOJI_REACTION"))),
                    "approvalEmojis" to JsonArray(listOf(JsonPrimitive("rocket"))),
                ),
            )
            val rocket = listOf(ApprovalSignal(User("bob", "Bob"), ApprovalMethod.EMOJI_REACTION, ":rocket:"))

            assertEquals(listOf("alice", "bob"), stage.apply(mr, cfg) { rocket }.approvals.map { it.user.username })
            assertEquals(listOf("alice"), stage.apply(mr, cfg) { signals }.approvals.map { it.user.username })
        }
}
