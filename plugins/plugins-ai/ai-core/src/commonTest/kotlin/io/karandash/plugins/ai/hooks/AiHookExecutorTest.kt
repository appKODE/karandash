package io.karandash.plugins.ai.hooks

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiHookAction
import io.karandash.plugins.ai.core.AiHookConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiHookExecutorTest {
    private class Service : AiService {
        val requests = mutableListOf<Pair<AiCompletionRequest, String?>>()
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            requests += request to providerId
            return AiCompletionResponse("reply ${requests.size}", 1, 1, 2, request.model)
        }
    }

    private fun executor(
        service: AiService,
        point: String,
        action: AiHookAction,
        prompt: String = "p",
    ) = AiHookExecutor(service, mapOf(point to AiHookConfig(prompt = prompt, action = action)), "default-model")

    @Test
    fun `no hook for the point returns empty without calling the service`() =
        runTest {
            val service = Service()

            val result = executor(service, "pre-report", AiHookAction.COMMENT).execute("post-validation", MR, listOf(A))

            assertEquals(AiHookResult.EMPTY, result)
            assertEquals(AiHookResult.EMPTY, executor(service, "pre-report", AiHookAction.COMMENT).executePerFinding(MR, listOf(A)))
            assertTrue(service.requests.isEmpty())
        }

    @Test
    fun `prompt template is filled and model and provider are passed`() =
        runTest {
            val service = Service()
            val hooks = mapOf(
                "post-validation" to
                    AiHookConfig(provider = "local", prompt = "{{mr_title}} {{mr_description}} {{changed_files}}", model = "m1"),
            )

            AiHookExecutor(service, hooks).execute("post-validation", MR, listOf(A))

            val (request, provider) = service.requests.single()
            assertEquals("Title desc src/A.kt, src/B.kt", request.prompt)
            assertEquals("m1", request.model)
            assertEquals("local", provider)
        }

    @Test
    fun `findings json lists the findings`() =
        runTest {
            val service = Service()

            executor(service, "post-validation", AiHookAction.LOG, "{{findings_json}}").execute("post-validation", MR, listOf(A))

            val prompt = service.requests
                .single()
                .first.prompt
            assertTrue("first issue" in prompt && "rule-a" in prompt, prompt)
            assertEquals(
                "default-model",
                service.requests
                    .single()
                    .first.model
            )
        }

    @Test
    fun `text from the merge request is not expanded as a template`() =
        runTest {
            val service = Service()
            val mr = MR.copy(description = "see {{mr_title}}")

            executor(service, "post-validation", AiHookAction.LOG, "{{mr_description}}").execute("post-validation", mr)

            assertEquals(
                "see {{mr_title}}",
                service.requests
                    .single()
                    .first.prompt
            )
        }

    @Test
    fun `placeholder next to extra braces is still filled`() =
        runTest {
            val service = Service()

            executor(service, "post-validation", AiHookAction.LOG, "{{{mr_title}}} {{nope}}").execute("post-validation", MR)

            assertEquals(
                "{Title} {{nope}}",
                service.requests
                    .single()
                    .first.prompt
            )
        }

    @Test
    fun `log action logs the reply and returns empty`() =
        runTest {
            val logged = mutableListOf<String>()

            val result = executor(Service(), "post-fetch", AiHookAction.LOG).execute("post-fetch", MR) { logged += it }

            assertEquals(AiHookResult.EMPTY, result)
            assertEquals(listOf("[ai-hook:post-fetch] reply 1"), logged)
        }

    @Test
    fun `comment metadata and finding actions shape the result`() =
        runTest {
            val point = "post-validation"

            assertEquals(listOf("reply 1"), executor(Service(), point, AiHookAction.COMMENT).execute(point, MR).comments)
            assertEquals(mapOf(point to "reply 1"), executor(Service(), point, AiHookAction.METADATA).execute(point, MR).metadata)
            val finding = executor(Service(), point, AiHookAction.FINDING).execute(point, MR).findings.single()
            assertEquals(Finding(Severity.INFO, "reply 1", "ai-hook-post-validation"), finding)
        }

    @Test
    fun `per-finding hook runs once per finding with that finding in the prompt`() =
        runTest {
            val service = Service()
            val hook = executor(service, "per-finding", AiHookAction.FINDING, "{{finding_rule}}: {{finding_message}}")

            val result = hook.executePerFinding(MR, listOf(A, B))

            assertEquals(listOf("rule-a: first issue", "rule-b: second issue"), service.requests.map { it.first.prompt })
            assertEquals(listOf("reply 1", "reply 2"), result.findings.map { it.message })
            assertTrue(result.findings.all { it.rule == "ai-hook-per-finding" && it.severity == Severity.INFO })
        }

    @Test
    fun `per-finding log comment and metadata actions`() =
        runTest {
            val logged = mutableListOf<String>()
            val log = executor(Service(), "per-finding", AiHookAction.LOG).executePerFinding(MR, listOf(A)) { logged += it }
            val comments = executor(Service(), "per-finding", AiHookAction.COMMENT).executePerFinding(MR, listOf(A, B))
            val metadata = executor(Service(), "per-finding", AiHookAction.METADATA).executePerFinding(MR, listOf(A, B))

            assertEquals(AiHookResult.EMPTY, log)
            assertEquals(listOf("[ai-hook:per-finding] reply 1"), logged)
            assertEquals(listOf("reply 1", "reply 2"), comments.comments)
            assertEquals(
                mapOf(AiHookExecutor.perFindingKey(A) to "reply 1", AiHookExecutor.perFindingKey(B) to "reply 2"),
                metadata.metadata
            )
        }

    @Test
    fun `merge concatenates findings and comments and unions metadata`() {
        val merged = AiHookResult.merge(
            listOf(
                AiHookResult(findings = listOf(A), comments = listOf("x"), metadata = mapOf("k" to "1")),
                AiHookResult(findings = listOf(B), comments = listOf("y"), metadata = mapOf("j" to "2")),
            ),
        )

        assertEquals(AiHookResult(listOf(A, B), listOf("x", "y"), mapOf("k" to "1", "j" to "2")), merged)
    }

    private companion object {
        val A = Finding(Severity.WARNING, "first issue", "rule-a")
        val B = Finding(Severity.ERROR, "second issue", "rule-b")
        val MR = MergeRequest(
            id = "1",
            iid = 1,
            title = "Title",
            description = "desc",
            sourceBranch = "f",
            targetBranch = "main",
            author = User(username = "dev", name = "Developer"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = listOf(
                FileChange(path = "src/A.kt", changeType = ChangeType.MODIFIED, diffContent = "+x"),
                FileChange(path = "src/B.kt", changeType = ChangeType.ADDED, diffContent = "+y"),
            ),
        )
    }
}
