@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.api.run.MergeRequestThreads
import io.karandash.api.run.RunServiceKeys
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.config.ThreadCommandsConfig
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreadAnswerStageTest {
    private class Threads(
        private val reply: String,
    ) : MergeRequestThreads {
        val replies = mutableListOf<String>()

        override suspend fun list() =
            listOf(
                ReviewThread(
                    "t1",
                    listOf(
                        ReviewNote("n1", "**Null check missing**\n" + InlineFindingMarker.of(FINDING), author = "bot"),
                        ReviewNote("n2", reply, author = "alice"),
                    ),
                ),
            )

        override suspend fun isMember(username: String) = username == "alice"

        override suspend fun botUsername() = "bot"

        override suspend fun resolve(
            threadId: String,
            note: String,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun reply(
            threadId: String,
            body: String,
        ): Result<Unit> = Result.success(Unit).also { replies += body }
    }

    private class Service : AiService {
        val requests = mutableListOf<AiCompletionRequest>()
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            requests += request
            return AiCompletionResponse("An answer.", 1, 1, 2, request.model)
        }
    }

    private suspend fun run(
        threads: Threads,
        config: AiPluginConfig,
        service: AiService? = null,
        raw: JsonObject? = null,
    ) {
        val session = ModuleRegistryImpl().forRun()
        session.provide(RunServiceKeys.REVIEW_THREADS, threads)
        withContext(session.services) {
            ThreadAnswerStage.run(config, raw, service, null, listOf(FINDING), MR, NoOpLogger)
        }
        session.close()
    }

    @Test
    fun `why is answered when feedback is on`() =
        runTest {
            val threads = Threads("@karandash why")
            run(threads, AiPluginConfig(feedbackEnabled = true))
            assertEquals(1, threads.replies.size)
            assertTrue("ai-code" in threads.replies.single(), threads.replies.toString())
        }

    @Test
    fun `feedbackEnabled set in the plugin config turns thread answers on`() =
        runTest {
            val off = Threads("@karandash why")
            val on = Threads("@karandash why")
            run(off, AiPluginConfig.fromPluginConfig(JsonObject(emptyMap())))
            run(on, AiPluginConfig.fromPluginConfig(JsonObject(mapOf("feedbackEnabled" to JsonPrimitive(true)))))
            assertEquals(emptyList(), off.replies)
            assertEquals(1, on.replies.size)
        }

    @Test
    fun `dry run posts no thread answer and makes no model call`() =
        runTest {
            val why = Threads("@karandash why")
            val ask = Threads("@karandash ask is it null?")
            val service = Service()
            val config = AiPluginConfig(feedbackEnabled = true, dryRunAi = true, threadCommands = ThreadCommandsConfig(ask = true))
            run(why, config, service)
            run(ask, config, service)
            assertEquals(emptyList(), why.replies + ask.replies)
            assertEquals(0, service.requests.size)
        }

    @Test
    fun `thread commands are not answered when feedback is off`() =
        runTest {
            val threads = Threads("@karandash why")
            run(threads, AiPluginConfig())
            assertEquals(emptyList(), threads.replies)
        }

    @Test
    fun `ask is disabled unless the key is set`() =
        runTest {
            val threads = Threads("@karandash ask is it null?")
            val service = Service()
            run(threads, AiPluginConfig(feedbackEnabled = true), service)
            assertTrue("disabled" in threads.replies.single(), threads.replies.toString())
            assertEquals(0, service.requests.size)
        }

    @Test
    fun `ask with the key set but no AI provider says no provider is configured`() =
        runTest {
            val threads = Threads("@karandash ask is it null?")
            run(threads, AiPluginConfig(feedbackEnabled = true, threadCommands = ThreadCommandsConfig(ask = true)), null)
            val reply = threads.replies.single()
            assertTrue("no AI provider" in reply, reply)
            assertTrue("ai.thread-commands.ask" !in reply, reply)
        }

    @Test
    fun `ask with the key set calls the model in the response language`() =
        runTest {
            val threads = Threads("@karandash ask is it null?")
            val service = Service()
            val config = AiPluginConfig(feedbackEnabled = true, threadCommands = ThreadCommandsConfig(ask = true))
            run(threads, config, service, JsonObject(mapOf("ai" to JsonObject(mapOf("responseLanguage" to JsonPrimitive("German"))))))
            assertTrue("An answer." in threads.replies.single(), threads.replies.toString())
            assertTrue(
                "Write the answer in German" in service.requests
                    .single()
                    .systemPrompt
                    .orEmpty()
            )
        }

    private companion object {
        val FINDING = Finding(Severity.WARNING, "Null check missing", "ai-code", Location.FileRef("src/A.kt", 3))
        val MR = MergeRequest(
            id = "1",
            iid = 1,
            title = "t",
            description = null,
            sourceBranch = "f",
            targetBranch = "main",
            author = User(username = "dev", name = "Developer"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = listOf(FileChange(path = "src/A.kt", changeType = ChangeType.MODIFIED, diffContent = "+x")),
        )
    }
}
