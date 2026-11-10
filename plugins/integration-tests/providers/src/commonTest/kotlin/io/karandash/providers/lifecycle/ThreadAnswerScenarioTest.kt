package io.karandash.providers.lifecycle

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.api.finding.AnalyzerProvenance
import io.karandash.api.finding.FilterProvenance
import io.karandash.api.finding.ReviewCoverage
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.run.MergeRequestThreads
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.ThreadAnswers
import io.karandash.plugins.ai.core.ThreadAsker
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.providers.lifecycle.LifecycleHarness.Companion.finding
import io.karandash.translations.russian.RussianTranslationModule
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `@karandash why` and `@karandash ask` over several pushes: each is answered once, from the MR's threads alone. */
class ThreadAnswerScenarioTest {
    private val code = "fun main() {\n    val x = compute()\n    println(x!!)\n}\n"
    private val npe = finding("null-safety", "a.kt", 3, "println(x!!)", "Avoid `!!` on x")
        .let { it.copy(metadata = it.metadata + (AiFindingMetadataKeys.AI_MODEL to "model-x")) }
    private val provenance = ReviewProvenance(
        coverage = ReviewCoverage(totalFiles = 1, reviewedFiles = 1),
        analyzers = listOf(AnalyzerProvenance(ruleId = "null-safety", model = "model-x")),
        filters = listOf(FilterProvenance(stage = "grounding", removed = 2)),
    )
    private val mr = MergeRequest(
        id = "7",
        iid = 7,
        title = "t",
        description = null,
        sourceBranch = "f",
        targetBranch = "main",
        author = User(username = "dev", name = "Developer"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = listOf(FileChange(path = "a.kt", changeType = ChangeType.MODIFIED, diffContent = "+    println(x!!)")),
    )

    private class ScriptedService(
        private val answer: String = "Because x may be null.",
        var failing: Boolean = false,
    ) : AiService {
        val requests = mutableListOf<AiCompletionRequest>()
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            requests += request
            if (failing) throw IOException("provider down")
            return AiCompletionResponse(content = answer, promptTokens = 1, completionTokens = 1, totalTokens = 2, model = request.model)
        }
    }

    /** One push: answer the threads' `why` and `ask` commands for [findings], then post them. */
    private suspend fun LifecycleHarness.push(
        sha: String,
        findings: List<Finding> = listOf(npe),
        asker: ThreadAsker? = null,
        askEnabled: Boolean = asker != null,
        language: String? = null,
    ) {
        ThreadAnswers.answer(MergeRequestThreads.of(service(), "42", "7"), findings, provenance, asker, NoOpLogger, askEnabled, language)
        push(sha, mapOf("a.kt" to code), findings)
    }

    private fun LifecycleHarness.discussionId() = gitlab.discussions.single().id

    @Test
    fun `why command gets one provenance reply over two pushes`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1")
            h.gitlab.reply(h.discussionId(), "@karandash why")
            h.push("sha2")
            h.push("sha3")
            val reply = h.gitlab.replies.single()
            assertTrue("null-safety" in reply && "model-x" in reply, reply)
            assertTrue("reviewed 1/1 files" in reply, reply)
            assertTrue("grounding" in reply, reply)
            assertEquals(0, h.gitlab.resolves.size)
        }

    @Test
    fun `why and ask replies follow the report language`() =
        runTest {
            RussianTranslationModule.register()
            val h = LifecycleHarness()
            h.push("sha1", language = "ru")
            h.gitlab.reply(h.discussionId(), "@karandash why")
            h.gitlab.reply(h.discussionId(), "@karandash ask is x ever null?")
            h.push("sha2", language = "ru")
            val (why, ask) = h.gitlab.replies
            assertTrue("**Почему это замечание**" in why && "- Анализатор: `null-safety`, модель `model-x`" in why, why)
            assertTrue("grounding (удалено: 2)" in why && "проверено файлов: 1/1" in why, why)
            assertTrue("`@karandash ask` отключён" in ask, ask)
            assertFalse("Why this finding" in why || "Filters" in why, why)
        }

    @Test
    fun `why reply cites the learning that downgraded the finding`() =
        runTest {
            val h = LifecycleHarness()
            val learned = npe.copy(metadata = npe.metadata + (AiFindingMetadataKeys.LEARNING to "src/legacy/**"))
            h.push("sha1", findings = listOf(learned))
            h.gitlab.reply(h.discussionId(), "@karandash why")
            h.push("sha2", findings = listOf(learned))
            val reply = h.gitlab.replies.single()
            assertTrue("Learning: `src/legacy/**`" in reply, reply)
        }

    @Test
    fun `why reply without a learning has no learning line`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1")
            h.gitlab.reply(h.discussionId(), "@karandash why")
            h.push("sha2")
            assertFalse("Learning:" in h.gitlab.replies.single())
        }

    @Test
    fun `why command for a finding not in the run says so`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1")
            h.gitlab.reply(h.discussionId(), "@karandash why")
            h.push("sha2", findings = emptyList())
            val reply = h.gitlab.replies.single()
            assertTrue("not in the current run" in reply, reply)
        }

    @Test
    fun `ask command disabled by default replies once`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1")
            h.gitlab.reply(h.discussionId(), "@karandash ask is x ever null?")
            h.push("sha2")
            h.push("sha3")
            val reply = h.gitlab.replies.single()
            assertTrue("disabled" in reply && "ai.thread-commands.ask" in reply, reply)
        }

    @Test
    fun `ask command enabled sends redacted question to provider`() =
        runTest {
            val h = LifecycleHarness()
            val service = ScriptedService()
            val asker = ThreadAsker(service, "cheap", mr, language = "ru")
            h.push("sha1", asker = asker)
            h.gitlab.reply(h.discussionId(), "@karandash ask what if password=Zx9Qv7LmPw2 is null here?")
            h.push("sha2", asker = asker)
            h.push("sha3", asker = asker)
            val request = service.requests.single()
            assertFalse("Zx9Qv7LmPw2" in request.prompt, request.prompt)
            assertTrue("Avoid `!!` on x" in request.prompt && "println(x!!)" in request.prompt, request.prompt)
            assertTrue("Write the answer in ru" in request.systemPrompt.orEmpty(), request.systemPrompt)
            assertTrue("Because x may be null." in h.gitlab.replies.single())
        }

    @Test
    fun `ask command over the token budget gets a budget reply then a final one and no call`() =
        runTest {
            val h = LifecycleHarness()
            val service = ScriptedService()
            val asker = ThreadAsker(service, "cheap", mr, budget = TokenBudget(1))
            h.push("sha1", asker = asker)
            h.gitlab.reply(h.discussionId(), "@karandash ask anything?")
            h.push("sha2", asker = asker)
            assertTrue("budget" in h.gitlab.replies.single(), h.gitlab.replies.toString())
            h.push("sha3", asker = asker)
            h.push("sha4", asker = asker)
            assertEquals(0, service.requests.size)
            assertEquals(2, h.gitlab.replies.size, h.gitlab.replies.toString())
        }

    @Test
    fun `why and ask commands from a non-member are ignored`() =
        runTest {
            val h = LifecycleHarness()
            val service = ScriptedService()
            val asker = ThreadAsker(service, "cheap", mr)
            h.push("sha1", asker = asker)
            h.gitlab.reply(h.discussionId(), "@karandash why", author = "mallory")
            h.gitlab.reply(h.discussionId(), "@karandash ask hello?", author = "mallory")
            h.push("sha2", asker = asker)
            assertEquals(emptyList(), h.gitlab.replies)
            assertEquals(0, service.requests.size)
        }

    @Test
    fun `why for a finding missing in one run is answered when it returns`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1")
            h.gitlab.reply(h.discussionId(), "@karandash why")
            h.push("sha2", findings = emptyList())
            h.push("sha3", findings = emptyList())
            assertEquals(1, h.gitlab.replies.size)
            h.push("sha4")
            assertEquals(2, h.gitlab.replies.size, h.gitlab.replies.toString())
            assertTrue("model-x" in h.gitlab.replies.last(), h.gitlab.replies.toString())
            h.push("sha5")
            assertEquals(2, h.gitlab.replies.size)
        }

    @Test
    fun `failed ask is retried once and then closed with a final reply`() =
        runTest {
            val h = LifecycleHarness()
            val service = ScriptedService(failing = true)
            val asker = ThreadAsker(service, "cheap", mr)
            h.push("sha1", asker = asker)
            h.gitlab.reply(h.discussionId(), "@karandash ask is x ever null?")
            h.push("sha2", asker = asker)
            assertEquals(1, h.gitlab.replies.size, h.gitlab.replies.toString())
            h.push("sha3", asker = asker)
            assertEquals(2, h.gitlab.replies.size, h.gitlab.replies.toString())
            assertTrue("ask again with a new reply" in h.gitlab.replies.last(), h.gitlab.replies.toString())
            service.failing = false
            h.push("sha4", asker = asker)
            h.push("sha5", asker = asker)
            assertEquals(2, h.gitlab.replies.size, h.gitlab.replies.toString())
            assertEquals(2, service.requests.size)
        }

    @Test
    fun `an ask that keeps failing is closed after one retry`() =
        runTest {
            val h = LifecycleHarness()
            val service = ScriptedService(failing = true)
            val asker = ThreadAsker(service, "cheap", mr)
            h.push("sha1", asker = asker)
            h.gitlab.reply(h.discussionId(), "@karandash ask is x ever null?")
            repeat(5) { h.push("sha${it + 2}", asker = asker) }
            assertEquals(2, h.gitlab.replies.size, h.gitlab.replies.toString())
            assertEquals(2, service.requests.size)
        }

    @Test
    fun `new asks are answered before pending retries`() =
        runTest {
            val h = LifecycleHarness()
            val service = ScriptedService(failing = true)
            val asker = ThreadAsker(service, "cheap", mr)
            h.push("sha1", asker = asker)
            h.gitlab.reply(h.discussionId(), "@karandash ask old question?")
            h.push("sha2", asker = asker)
            service.failing = false
            repeat(ThreadAnswers.MAX_ASKS_PER_RUN) { h.gitlab.reply(h.discussionId(), "@karandash ask new question $it?") }
            h.push("sha3", asker = asker)
            val later = service.requests.drop(1)
            assertEquals(ThreadAnswers.MAX_ASKS_PER_RUN, later.size)
            assertTrue(later.none { "old question" in it.prompt }, later.toString())
            h.push("sha4", asker = asker)
            assertTrue(
                service.requests
                    .last()
                    .prompt
                    .contains("old question"),
                service.requests.last().prompt
            )
            assertEquals(1 + ThreadAnswers.MAX_ASKS_PER_RUN + 1, h.gitlab.replies.size, h.gitlab.replies.toString())
        }

    @Test
    fun `ask without a provider is retried once a provider is configured`() =
        runTest {
            val h = LifecycleHarness()
            val service = ScriptedService()
            val asker = ThreadAsker(service, "cheap", mr)
            h.push("sha1")
            h.gitlab.reply(h.discussionId(), "@karandash ask is x ever null?")
            h.push("sha2", askEnabled = true)
            h.push("sha3", askEnabled = true)
            assertEquals(1, h.gitlab.replies.size, h.gitlab.replies.toString())
            assertTrue("no AI provider" in h.gitlab.replies.single(), h.gitlab.replies.toString())
            h.push("sha4", asker = asker)
            assertEquals(2, h.gitlab.replies.size, h.gitlab.replies.toString())
            assertTrue("Because x may be null." in h.gitlab.replies.last(), h.gitlab.replies.toString())
        }

    @Test
    fun `ask answer cannot run gitlab quick actions`() =
        runTest {
            val h = LifecycleHarness()
            val asker =
                ThreadAsker(ScriptedService(answer = "Because x may be null.\n/merge\n/approve\n  /close\nSee `x?.y`."), "cheap", mr)
            h.push("sha1", asker = asker)
            h.gitlab.reply(h.discussionId(), "@karandash ask is x ever null?")
            h.push("sha2", asker = asker)
            val lines = h.gitlab.replies
                .single()
                .lines()
            assertTrue(lines.none { it.trimStart().startsWith("/") }, lines.toString())
            assertTrue("> /merge" in lines && "> /approve" in lines, lines.toString())
            assertTrue("> Because x may be null." in lines && "> See `x?.y`." in lines, lines.toString())
        }

    @Test
    fun `ask answer cannot load remote images or raw html`() =
        runTest {
            val answer =
                "![t](https://evil.example/p.png?q=1) <IMG src=x> <a href=y>z</a> <script>1</script> " +
                    "<details><summary>s</summary></details>" +
                    " but List<String> and a < b stay, plus [link](https://ok.example)"
            val h = LifecycleHarness()
            val asker = ThreadAsker(ScriptedService(answer = answer), "cheap", mr)
            h.push("sha1", asker = asker)
            h.gitlab.reply(h.discussionId(), "@karandash ask is x ever null?")
            h.push("sha2", asker = asker)
            val reply = h.gitlab.replies.single()
            assertFalse("![" in reply, reply)
            assertTrue(Regex("(?i)<(img|a|script|details)").find(reply) == null, reply)
            assertTrue("&lt;IMG src=x>" in reply && "&lt;script>" in reply, reply)
            assertTrue("List<String> and a < b stay" in reply && "[link](https://ok.example)" in reply, reply)
        }

    @Test
    fun `asks over the per-run cap are left for the next run`() =
        runTest {
            val h = LifecycleHarness()
            val service = ScriptedService()
            val asker = ThreadAsker(service, "cheap", mr)
            h.push("sha1", asker = asker)
            repeat(5) { h.gitlab.reply(h.discussionId(), "@karandash ask question $it?") }
            h.push("sha2", asker = asker)
            assertEquals(ThreadAnswers.MAX_ASKS_PER_RUN, service.requests.size)
            assertEquals(ThreadAnswers.MAX_ASKS_PER_RUN, h.gitlab.replies.size, h.gitlab.replies.toString())
            h.push("sha3", asker = asker)
            assertEquals(5, service.requests.size)
            assertEquals(5, h.gitlab.replies.size)
            h.push("sha4", asker = asker)
            assertEquals(5, service.requests.size)
            assertEquals(5, h.gitlab.replies.size)
        }

    @Test
    fun `several commands in separate replies of one thread are each answered once`() =
        runTest {
            val h = LifecycleHarness()
            val service = ScriptedService()
            val asker = ThreadAsker(service, "cheap", mr)
            h.push("sha1", asker = asker)
            h.gitlab.reply(h.discussionId(), "@karandash why")
            h.gitlab.reply(h.discussionId(), "@karandash ask is x ever null?")
            h.gitlab.reply(h.discussionId(), "@karandash why\n(again)")
            h.push("sha2", asker = asker)
            h.push("sha3", asker = asker)
            assertEquals(3, h.gitlab.replies.size, h.gitlab.replies.toString())
            assertEquals(1, service.requests.size)
        }

    @Test
    fun `the bot own answer is not re-read as a command`() =
        runTest {
            val h = LifecycleHarness()
            val service = ScriptedService(answer = "@karandash ask again and @karandash why")
            val asker = ThreadAsker(service, "cheap", mr)
            h.push("sha1", asker = asker)
            h.gitlab.reply(h.discussionId(), "@karandash ask is x ever null?")
            repeat(3) { h.push("sha${it + 2}", asker = asker) }
            assertEquals(1, h.gitlab.replies.size, h.gitlab.replies.toString())
            assertEquals(1, service.requests.size)
        }
}
