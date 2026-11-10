package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CertificateCheckTest {
    private val changes = listOf(
        FileChange("src/Foo.kt", ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = "+val x = input!!"),
    )

    private val certificate =
        """
        ### EVIDENCE
        - Claim: unsafe call
        - Supporting code: [src/Foo.kt:10] `val x = input!!`
        - Claim: invented
        - Supporting code: [src/Foo.kt:20] `val y = nope()`
        - Claim: other file
        - Supporting code: [src/Ghost.kt:5] `ghost()`
        ### FINDINGS
        []
        """.trimIndent()

    private class Replying(
        private val reply: String,
    ) : AiService {
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ) = AiCompletionResponse(content = reply, promptTokens = 1, completionTokens = 1, totalTokens = 2, model = "m")
    }

    private fun finding(
        path: String,
        line: Int,
    ) = Finding(severity = Severity.WARNING, message = "m", rule = "ai-review", location = Location.FileRef(path, line))

    @Test
    fun `findings at unverified certificate citations are dropped`() =
        runTest {
            val check = CertificateCheck(Replying(certificate))
            check.complete(AiCompletionRequest(prompt = "p", model = "m"), null)
            val findings = listOf(finding("src/Foo.kt", 10), finding("src/Foo.kt", 20), finding("src/Ghost.kt", 5))

            val kept = check.filter(findings, changes, "ai-review", NoOpLogger)

            assertEquals(listOf(finding("src/Foo.kt", 10)), kept)
        }

    @Test
    fun `without a certificate nothing is dropped`() {
        val findings = listOf(finding("src/Foo.kt", 20))
        assertEquals(findings, CertificateCheck(Replying("")).filter(findings, changes, "ai-review", NoOpLogger))
    }
}
