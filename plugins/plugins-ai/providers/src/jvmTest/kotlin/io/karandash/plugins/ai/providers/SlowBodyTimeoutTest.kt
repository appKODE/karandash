package io.karandash.plugins.ai.providers

import io.karandash.api.ai.AiProviderException
import io.karandash.duration.KarandashDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A local server sends the status line and headers at once, then the body a byte at a time. Every byte arrives well
 * inside the socket (idle) timeout, so only a bound on the whole call stops it at `ai.timeout`. Real CIO engine.
 */
class SlowBodyTimeoutTest {
    private val server = ServerSocket(0)

    @AfterTest
    fun close() = server.close()

    private val okBody =
        """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,""" +
            """"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],""" +
            """"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""

    /** Answers every connection: headers now, then [padding] spaces one per [gapMs], then the JSON. */
    private fun serveTrickle(
        padding: Int,
        gapMs: Long,
        status: String = "200 OK",
    ) = serve { trickle(it, padding, gapMs, status) }

    private fun serve(answer: (Socket) -> Unit) =
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = accept() ?: return@thread
                thread(isDaemon = true) { answer(socket) }
            }
        }

    /** Streaming headers, then nothing for [silenceMs]. */
    private fun silentStream(
        socket: Socket,
        silenceMs: Long,
    ) = socket.use { s ->
        readRequest(s)
        try {
            s.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                flush()
            }
            Thread.sleep(silenceMs)
        } catch (_: IOException) {
            // the client gave up, which is what the test wants
        }
    }

    private fun readRequest(s: Socket) {
        val input = s.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
        val length = CONTENT_LENGTH
            .find(head)
            ?.groupValues
            ?.get(1)
            ?.toInt() ?: 0
        repeat(length) { input.read() }
    }

    private fun accept(): Socket? =
        try {
            server.accept()
        } catch (_: IOException) {
            null
        }

    private fun trickle(
        socket: Socket,
        padding: Int,
        gapMs: Long,
        status: String,
    ) = socket.use { s ->
        readRequest(s)
        val out = s.getOutputStream()
        val body = " ".repeat(padding) + okBody
        try {
            out.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n".toByteArray())
            out.flush()
            repeat(padding) {
                Thread.sleep(gapMs)
                out.write(' '.code)
                out.flush()
            }
            out.write(okBody.toByteArray())
            out.flush()
        } catch (_: IOException) {
            // the client gave up, which is what the test wants
        }
    }

    @Test
    fun `a body that trickles past ai timeout fails at ai timeout`() =
        runTest(timeout = 60.seconds) {
            serveTrickle(padding = 16, gapMs = 500)
            val provider =
                OllamaProvider(
                    baseUrl = "http://127.0.0.1:${server.localPort}/v1",
                    timeout = KarandashDuration.ofSeconds(2),
                    maxRetries = 0,
                )
            val started = TimeSource.Monotonic.markNow()
            val e =
                withContext(Dispatchers.Default) {
                    assertFailsWith<AiProviderException> { provider.complete(prompt = "hi", model = "m") }
                }
            val took = started.elapsedNow()
            provider.close()
            assertTrue(took < 5.seconds, "the call took $took, past ai.timeout 2s: ${e.message}")
            assertTrue(e.message.orEmpty().contains("2s"), "names the effective timeout: ${e.message}")
        }

    @Test
    fun `an error body that trickles past ai timeout fails at ai timeout`() =
        runTest(timeout = 60.seconds) {
            serveTrickle(padding = 16, gapMs = 500, status = "503 Service Unavailable")
            val provider =
                OllamaProvider(
                    baseUrl = "http://127.0.0.1:${server.localPort}/v1",
                    timeout = KarandashDuration.ofSeconds(2),
                    maxRetries = 0,
                )
            val started = TimeSource.Monotonic.markNow()
            val e =
                withContext(Dispatchers.Default) {
                    assertFailsWith<AiProviderException> { provider.complete(prompt = "hi", model = "m") }
                }
            val took = started.elapsedNow()
            provider.close()
            assertTrue(took < 5.seconds, "the error body read took $took, past ai.timeout 2s: ${e.message}")
            assertTrue(e.retryable, "a 503 whose body never arrives is still retryable: ${e.message}")
        }

    @Test
    fun `a stream that goes silent fails at the idle timeout`() =
        runTest(timeout = 60.seconds) {
            serve { silentStream(it, silenceMs = 10_000) }
            val provider =
                OllamaProvider(
                    baseUrl = "http://127.0.0.1:${server.localPort}/v1",
                    timeout = KarandashDuration.ofSeconds(2),
                    maxRetries = 0,
                )
            provider.configure(apiKey = null, baseUrl = null, stream = true)
            val started = TimeSource.Monotonic.markNow()
            val e =
                withContext(Dispatchers.Default) {
                    assertFails { provider.complete(prompt = "hi", model = "m") }
                }
            val took = started.elapsedNow()
            provider.close()
            assertTrue(took < 6.seconds, "the stream waited $took past its 2s idle timeout: $e")
        }

    private companion object {
        val CONTENT_LENGTH = Regex("(?i)content-length: (\\d+)")
    }
}
