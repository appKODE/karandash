package io.karandash.plugins.ai.core.pipeline

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiService
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.TokenBudget
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindingVerifierTest {
    private class ScriptedService(
        private val reply: (AiCompletionRequest) -> String,
    ) : AiService {
        val requests = mutableListOf<AiCompletionRequest>()
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            requests += request
            return AiCompletionResponse(
                content = reply(request),
                promptTokens = 1,
                completionTokens = 1,
                totalTokens = 2,
                model = request.model
            )
        }
    }

    private val failing =
        object : AiService {
            override val isAvailable = true

            override suspend fun complete(
                request: AiCompletionRequest,
                providerId: String?,
            ): AiCompletionResponse = throw AiProviderException("timeout", statusCode = 504, retryable = true)
        }

    private val mr =
        MergeRequest(
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
            changes = listOf(
                FileChange(path = "A.kt", changeType = ChangeType.MODIFIED, diffContent = "@@ -1 +1 @@\n-val a = 1\n+val a = 2")
            ),
        )

    private fun finding(
        message: String,
        severity: Severity = Severity.WARNING,
        ai: Boolean = true,
    ) = Finding(
        severity = severity,
        message = message,
        rule = if (ai) "review-comments" else "title-format",
        location = Location.FileRef("A.kt", 1),
        metadata = if (ai) mapOf(AiFindingMetadataKeys.AI_PROVIDER to "openai") else emptyMap(),
    )

    @Test
    fun `an invalid finding is dropped and a valid one is kept`() =
        runTest {
            val service = ScriptedService {
                """{"verdicts":[{"index":0,"verdict":"invalid","reason":"line 1 sets a constant"},""" +
                    """{"index":1,"verdict":"valid","reason":"value changed"}]}"""
            }
            val bogus = finding("null dereference on a")
            val real = finding("value of a changed without a test")

            val result = FindingVerifier(service, "cheap").secondOpinion(listOf(bogus, real), mr)

            assertEquals(listOf(real), result)
        }

    @Test
    fun `a provider error keeps every finding`() =
        runTest {
            val findings = listOf(finding("x"), finding("y"))

            assertEquals(findings, FindingVerifier(failing, "cheap").secondOpinion(findings, mr))
        }

    @Test
    fun `an unparseable reply keeps every finding`() =
        runTest {
            val findings = listOf(finding("x"))

            assertEquals(findings, FindingVerifier(ScriptedService { "not json" }, "cheap").secondOpinion(findings, mr))
        }

    @Test
    fun `a finding the model gave no verdict for is kept`() =
        runTest {
            val service = ScriptedService { """{"verdicts":[{"index":0,"verdict":"invalid","reason":"r"}]}""" }
            val second = finding("y")

            assertEquals(listOf(second), FindingVerifier(service, "cheap").secondOpinion(listOf(finding("x"), second), mr))
        }

    @Test
    fun `info and non ai and file-less findings are not sent and always kept`() =
        runTest {
            val service = ScriptedService { error("must not be called") }
            val noFile = finding("mr level", Severity.ERROR).copy(location = null)
            val findings = listOf(finding("i", Severity.INFO), finding("t", ai = false), noFile)

            assertEquals(findings, FindingVerifier(service, "cheap").secondOpinion(findings, mr))
            assertTrue(service.requests.isEmpty())
        }

    @Test
    fun `a prompt over the budget skips the call and keeps every finding`() =
        runTest {
            val service = ScriptedService { error("must not be called") }
            val findings = listOf(finding("x".repeat(FindingVerifier.MAX_PROMPT_CHARS)))

            assertEquals(findings, FindingVerifier(service, "cheap").secondOpinion(findings, mr))
            assertTrue(service.requests.isEmpty())
        }

    @Test
    fun `the request uses the given model and asks for the verdict before the reason`() =
        runTest {
            val service = ScriptedService { """{"verdicts":[]}""" }

            FindingVerifier(service, "cheap").secondOpinion(listOf(finding("x")), mr)

            val request = service.requests.single()
            assertEquals("cheap", request.model)
            val system = checkNotNull(request.systemPrompt)
            assertTrue(system.indexOf("\"verdict\"") in 0 until system.indexOf("\"reason\""), system)
            assertTrue(request.prompt.contains("A.kt:1"), request.prompt)
            assertTrue(request.prompt.contains("+val a = 2"), request.prompt)
        }

    @Test
    fun `a secret in the diff never reaches the verifier prompt`() =
        runTest {
            val service = ScriptedService { """{"verdicts":[]}""" }
            val secret = "AKIAABCDEFGHIJKLMNOP"
            val leaky = mr.copy(
                changes = listOf(
                    FileChange(
                        path = "A.kt",
                        changeType = ChangeType.MODIFIED,
                        diffContent = "@@ -1 +1,2 @@\n+val key = \"$secret\"\n+val a = 2",
                    ),
                ),
            )

            FindingVerifier(service, "cheap").secondOpinion(listOf(finding("x")), leaky)

            val prompt = service.requests.single().prompt
            assertTrue(secret !in prompt, prompt)
            assertTrue(prompt.contains("+val a = 2"), prompt)
        }

    @Test
    fun `the verifier call is recorded in the token budget and holds a concurrency permit`() =
        runTest {
            val permits = Semaphore(1)
            val heldDuringCall = mutableListOf<Int>()
            val service = ScriptedService {
                heldDuringCall += permits.availablePermits
                """{"verdicts":[]}"""
            }
            val budget = TokenBudget(100_000)

            FindingVerifier(service, "cheap", budget, permits).secondOpinion(listOf(finding("x")), mr)

            assertEquals(2, budget.totalConsumed())
            assertEquals(listOf(0), heldDuringCall)
            assertEquals(1, permits.availablePermits)
        }

    @Test
    fun `an exhausted budget skips the call and keeps every finding`() =
        runTest {
            val service = ScriptedService { error("must not be called") }
            val findings = listOf(finding("x"))

            assertEquals(findings, FindingVerifier(service, "cheap", TokenBudget(10)).secondOpinion(findings, mr))
            assertTrue(service.requests.isEmpty())
        }
}
