package io.karandash.plugins.ai

import io.karandash.api.ai.AiProviderException
import kotlin.test.Test
import kotlin.test.assertEquals

/** The skip reason of a failed call names the cause and never carries a url, path or response body. */
class AiRuleErrorHandlersTest {
    private fun reason(e: Exception) = AiRuleErrorHandlers.providerErrorReason(e)

    @Test
    fun `an unreachable host is named by its host from anywhere in the cause chain`() {
        val unreachable = AiProviderException("unreachable: proxy.example (connect timeout)")
        assertEquals("provider error: unreachable: proxy.example (connect timeout)", reason(unreachable))
        assertEquals(
            "provider error: unreachable: proxy.example (connect timeout)",
            reason(AiProviderException("wrapped", cause = unreachable))
        )
    }

    @Test
    fun `a timeout message is timed out without the url`() {
        val ktor = AiProviderException("HTTP request failed: Socket timeout has expired [url=https://h.example/v1?key=s]")
        assertEquals("provider error: timed out", reason(ktor))
        assertEquals("provider error: timed out", reason(AiProviderException("Request timed out after 5m")))
        assertEquals("provider error: timed out", reason(AiProviderException("x", cause = IllegalStateException("read timeout"))))
    }

    @Test
    fun `a status is HTTP code without the body`() {
        assertEquals("provider error: HTTP 400", reason(AiProviderException("Unexpected status 400: secret body", statusCode = 400)))
        assertEquals("provider error: call failed, see the job log", reason(AiProviderException("boom")))
    }
}
