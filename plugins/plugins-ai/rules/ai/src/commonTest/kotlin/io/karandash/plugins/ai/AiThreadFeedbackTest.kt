@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.api.finding.ReviewThreadPosition
import io.karandash.api.run.MergeRequestThreads
import io.karandash.api.run.RunServiceKeys
import io.karandash.api.support.mapToJsonElement
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.LlmReplyJudge
import io.karandash.plugins.ai.core.ReplyJudge
import io.karandash.plugins.ai.core.ReplyJudgeMode
import io.karandash.plugins.ai.core.ReplyVerdict
import io.karandash.testfixtures.TestPluginContext
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiThreadFeedbackTest {
    private class Threads(
        private val reply: String,
    ) : MergeRequestThreads {
        val resolved = mutableListOf<String>()
        val replies = mutableListOf<String>()
        private val listCount = atomic(0)
        val lists: Int get() = listCount.value

        override suspend fun list() =
            listOf(
                ReviewThread(
                    "t1",
                    listOf(
                        ReviewNote("n1", "**Null check missing**\n" + InlineFindingMarker.of(FINDING), author = "bot"),
                        ReviewNote("n2", reply, author = "alice"),
                    ),
                    position = ReviewThreadPosition(newPath = "src/A.kt", newLine = 3),
                ),
            ).also {
                listCount.incrementAndGet()
                delay(LIST_DELAY_MS)
            }

        override suspend fun isMember(username: String) = username == "alice"

        override suspend fun botUsername() = "bot"

        override suspend fun resolve(
            threadId: String,
            note: String,
        ): Result<Unit> = Result.success(Unit).also { resolved += threadId }

        override suspend fun reply(
            threadId: String,
            body: String,
        ): Result<Unit> = Result.success(Unit).also { replies += body }
    }

    @Test
    fun `a member ignore command on the run MR dismisses the finding and resolves the thread`() =
        runTest {
            val threads = Threads("@karandash ignore")
            val session = ModuleRegistryImpl().forRun()
            session.provide(RunServiceKeys.REVIEW_THREADS, threads)
            val store = FeedbackStore()

            withContext(session.services) { AiMrStatePersistence.applyThreadFeedback(store, TestPluginContext().logger) }
            session.close()

            assertTrue(store.isDismissed("ai-code", "src/A.kt", FINDING.message.hashCode()))
            assertEquals(listOf("t1"), threads.resolved)
        }

    @Test
    fun `dry run learns from a command but posts nothing to the thread`() =
        runTest {
            val ignore = Threads("@karandash ignore")
            val help = Threads("@karandash frobnicate")
            val store = FeedbackStore()
            val session = ModuleRegistryImpl().forRun()
            session.provide(RunServiceKeys.REVIEW_THREADS, ignore)
            withContext(session.services) {
                AiMrStatePersistence.applyThreadFeedback(store, TestPluginContext().logger, dryRun = true)
            }
            session.provide(RunServiceKeys.REVIEW_THREADS, help)
            withContext(session.services) {
                AiMrStatePersistence.applyThreadFeedback(FeedbackStore(), TestPluginContext().logger, dryRun = true)
            }
            session.close()

            assertTrue(store.isDismissed("ai-code", "src/A.kt", FINDING.message.hashCode()))
            assertEquals(emptyList(), ignore.resolved)
            assertEquals(emptyList(), ignore.replies)
            assertEquals(emptyList(), help.replies)
        }

    @Test
    fun `an unknown reply goes to the given judge`() =
        runTest {
            val session = ModuleRegistryImpl().forRun()
            session.provide(RunServiceKeys.REVIEW_THREADS, Threads("see the handler upstream"))
            val store = FeedbackStore()
            val judge = ReplyJudge { _, excerpt -> if ("Null check missing" in excerpt) ReplyVerdict.REJECTED else ReplyVerdict.UNKNOWN }

            withContext(session.services) { AiMrStatePersistence.applyThreadFeedback(store, TestPluginContext().logger, judge = judge) }
            session.close()

            assertTrue(store.isDismissed("ai-code", "src/A.kt", FINDING.message.hashCode()))
        }

    @Test
    fun `reply judge is built only for llm mode with a service outside a dry run`() {
        val service = object : AiService {
            override val isAvailable = true

            override suspend fun complete(
                request: AiCompletionRequest,
                providerId: String?,
            ): AiCompletionResponse = error("not called")
        }
        val llm = AiPluginConfig(feedbackReplyJudge = ReplyJudgeMode.LLM)

        assertNull(AiMrStatePersistence.replyJudge(AiPluginConfig(), service))
        assertNull(AiMrStatePersistence.replyJudge(llm, null))
        assertNull(AiMrStatePersistence.replyJudge(llm.copy(dryRunAi = true), service))
        assertIs<LlmReplyJudge>(AiMrStatePersistence.replyJudge(llm, service))
    }

    @Test
    fun `reply judge off never calls the AI service for an undecidable reply`() =
        runTest {
            var calls = 0
            val service = object : AiService {
                override val isAvailable = true

                override suspend fun complete(
                    request: AiCompletionRequest,
                    providerId: String?,
                ): AiCompletionResponse {
                    calls++
                    return AiCompletionResponse("""{"verdict":"REJECTED"}""", 1, 1, 2, request.model)
                }
            }

            suspend fun run(config: AiPluginConfig): FeedbackStore {
                val session = ModuleRegistryImpl().forRun()
                session.provide(RunServiceKeys.REVIEW_THREADS, Threads("see the handler upstream"))
                val store = FeedbackStore()
                val judge = AiMrStatePersistence.replyJudge(config, service)
                withContext(session.services) { AiMrStatePersistence.applyThreadFeedback(store, TestPluginContext().logger, judge = judge) }
                session.close()
                return store
            }

            assertEquals(0, run(AiPluginConfig(feedbackEnabled = true)).dismissCount())
            assertEquals(0, calls)
            assertEquals(1, run(AiPluginConfig(feedbackEnabled = true, feedbackReplyJudge = ReplyJudgeMode.LLM)).dismissCount())
            assertEquals(1, calls)
        }

    @Test
    fun `rules validating in parallel read thread feedback once per run`() =
        runTest {
            val threads = Threads("False positive, x is never null")
            val config = mapOf(
                "provider" to "custom",
                "baseUrl" to "http://localhost:1234/v1",
                "model" to "m",
                "dryRun" to true,
                "feedbackEnabled" to true
            )
            val module = AiRuleModule(buildTestRegistry())
            module.initialize(TestPluginContext(pluginConfig = mapToJsonElement(config)))
            val session = ModuleRegistryImpl().forRun()
            session.provide(RunServiceKeys.REVIEW_THREADS, threads)

            withContext(Dispatchers.Default + session.services) {
                List(PARALLEL_RULES) { async { module.validate("risk-scoring", MR, JsonObject(emptyMap())) } }.awaitAll()
            }
            session.close()
            module.shutdown()

            assertEquals(1, threads.lists)
        }

    @Test
    fun `no review threads service leaves the store untouched`() =
        runTest {
            val store = FeedbackStore()
            AiMrStatePersistence.applyThreadFeedback(store, TestPluginContext().logger)
            assertEquals(0, store.dismissCount())
        }

    private companion object {
        const val LIST_DELAY_MS = 50L
        const val PARALLEL_RULES = 4
        val MR = MergeRequest(
            id = "1",
            iid = 1,
            title = "t",
            description = "d",
            sourceBranch = "f",
            targetBranch = "main",
            author = User("u1", "dev", "Developer"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = listOf(FileChange("src/A.kt", ChangeType.MODIFIED, additions = 1, deletions = 0)),
        )
        val FINDING = Finding(Severity.WARNING, "Null check missing", "ai-code", Location.FileRef("src/A.kt", 3))
    }
}
