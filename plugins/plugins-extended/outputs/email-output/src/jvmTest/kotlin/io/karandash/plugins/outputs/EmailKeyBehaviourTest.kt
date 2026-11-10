package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One behaviour test per `email` output key, against a local plain SMTP relay that records what it receives. */
class EmailKeyBehaviourTest {
    private val base = EmailOutputConfig(host = "127.0.0.1", from = "bot@example.com", to = "team@example.com", starttls = false)

    /** Runs the module against a relay, returns every line the relay saw and the decoded AUTH credentials. */
    private suspend fun send(
        config: (port: Int) -> EmailOutputConfig,
        offerAuth: Boolean = false,
    ): Pair<List<String>, List<String>> {
        val received = Collections.synchronizedList(mutableListOf<String>())
        val credentials = Collections.synchronizedList(mutableListOf<String>())
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val smtp = thread(isDaemon = true) { serveRelay(server, received, credentials, offerAuth) }
            val context = TestPluginContext()
            val module = EmailOutputModule()
            assertTrue(module.initialize(context))
            module.outputTyped(FormattedReport(formatId = "html", body = "<p>report</p>"), config(server.localPort))
            smtp.join(SERVER_JOIN_MS)
            assertTrue(context.warnings.isEmpty(), "${context.warnings}")
        }
        return received.toList() to credentials.toList()
    }

    @Test
    fun `host is the SMTP server the message goes to`() =
        runTest {
            val (received, _) = send({ port -> base.copy(port = port) })

            assertTrue(received.any { it.startsWith("MAIL FROM:") }, "server saw: $received")
        }

    @Test
    fun `port is the SMTP port the message goes to`() =
        runTest {
            val (received, _) = send({ port -> base.copy(port = port) })

            assertTrue(received.any { it.startsWith("EHLO") }, "server saw: $received")
        }

    @Test
    fun `from is the envelope sender`() =
        runTest {
            val (received, _) = send({ port -> base.copy(port = port, from = "sender@example.com") })

            assertTrue(received.any { it.startsWith("MAIL FROM:<sender@example.com>") }, "server saw: $received")
        }

    @Test
    fun `to is the envelope recipient`() =
        runTest {
            val (received, _) = send({ port -> base.copy(port = port, to = "other@example.com") })

            assertTrue(received.any { it.startsWith("RCPT TO:<other@example.com>") }, "server saw: $received")
        }

    @Test
    fun `subject is the message subject`() =
        runTest {
            val (received, _) = send({ port -> base.copy(port = port, subject = "Custom subject") })

            assertTrue("Subject: Custom subject" in received, "server saw: $received")
        }

    @Test
    fun `starttls false sends plaintext to a loopback relay without STARTTLS`() =
        runTest {
            val (received, _) = send({ port -> base.copy(port = port, starttls = false) })

            assertTrue(received.none { it.startsWith("STARTTLS") }, "server saw: $received")
            assertTrue(received.any { it.startsWith("MAIL FROM:") }, "server saw: $received")
        }

    @Test
    fun `username is the SMTP login and the sender when from is unset`() =
        runTest {
            val (received, credentials) =
                send({ port -> base.copy(port = port, from = null, username = "login@example.com", password = "pw") }, offerAuth = true)

            assertTrue(received.any { it.startsWith("MAIL FROM:<login@example.com>") }, "server saw: $received")
            assertTrue(credentials.any { "\u0000login@example.com\u0000" in it }, "credentials: $credentials")
        }

    @Test
    fun `password is sent in the SMTP login`() =
        runTest {
            val (_, credentials) =
                send({ port -> base.copy(port = port, username = "login@example.com", password = "s3cret-pw") }, offerAuth = true)

            assertEquals(1, credentials.count { it.endsWith("\u0000s3cret-pw") }, "credentials: $credentials")
        }

    /** Plain SMTP relay; with [offerAuth] it advertises AUTH PLAIN and records the decoded credentials. */
    private fun serveRelay(
        server: ServerSocket,
        received: MutableList<String>,
        credentials: MutableList<String>,
        offerAuth: Boolean,
    ) {
        server.accept().use { socket ->
            val out = socket.getOutputStream().bufferedWriter()
            val input = socket.getInputStream().bufferedReader()
            out.write("220 relay ESMTP\r\n")
            out.flush()
            var inData = false
            var awaitCredential = false
            generateSequence { input.readLine() }.forEach { line ->
                received += line
                val reply = when {
                    awaitCredential -> {
                        awaitCredential = false
                        credentials += Base64.getDecoder().decode(line).decodeToString()
                        "235 ok\r\n"
                    }

                    inData && line == "." -> {
                        "250 queued\r\n".also { inData = false }
                    }

                    inData -> {
                        null
                    }

                    line.startsWith("EHLO") -> {
                        if (offerAuth) "250-relay\r\n250 AUTH PLAIN\r\n" else "250 relay\r\n"
                    }

                    line == "AUTH PLAIN" -> {
                        "334 \r\n".also { awaitCredential = true }
                    }

                    line.startsWith("AUTH PLAIN ") -> {
                        credentials += Base64.getDecoder().decode(line.removePrefix("AUTH PLAIN ")).decodeToString()
                        "235 ok\r\n"
                    }

                    line.startsWith("DATA") -> {
                        "354 go\r\n".also { inData = true }
                    }

                    line.startsWith("QUIT") -> {
                        "221 bye\r\n"
                    }

                    else -> {
                        "250 ok\r\n"
                    }
                }
                if (reply != null) {
                    out.write(reply)
                    out.flush()
                }
                if (line.startsWith("QUIT")) return
            }
        }
    }

    private companion object {
        const val SERVER_JOIN_MS = 10_000L
    }
}
