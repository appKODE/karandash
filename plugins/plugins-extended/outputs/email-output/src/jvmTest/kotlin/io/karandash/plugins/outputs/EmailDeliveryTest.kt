package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Delivery goes to the configured SMTP host and port and insists on STARTTLS by default: against a local SMTP server that
 * does not offer it, the module greets the server, sends nothing (no AUTH, no MAIL FROM) and warns instead of throwing.
 */
class EmailDeliveryTest {
    @Test
    fun deliveryReachesTheConfiguredServerAndRefusesPlaintext() =
        runTest {
            val received = Collections.synchronizedList(mutableListOf<String>())
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val smtp = thread(isDaemon = true) {
                    server.accept().use { socket ->
                        val out = socket.getOutputStream().bufferedWriter()
                        val input = socket.getInputStream().bufferedReader()
                        out.write("220 fake ESMTP\r\n")
                        out.flush()
                        generateSequence { input.readLine() }.forEach { line ->
                            received += line
                            val reply = when {
                                line.startsWith("EHLO") -> "250-fake\r\n250 AUTH PLAIN\r\n"
                                line.startsWith("QUIT") -> "221 bye\r\n"
                                else -> "250 ok\r\n"
                            }
                            out.write(reply)
                            out.flush()
                            if (line.startsWith("QUIT")) return@use
                        }
                    }
                }
                val context = TestPluginContext()
                val module = EmailOutputModule()
                assertTrue(module.initialize(context))
                module.outputTyped(
                    FormattedReport(formatId = "html", body = "<p>report</p>"),
                    EmailOutputConfig(host = "127.0.0.1", port = server.localPort, username = "bot@example.com", to = "team@example.com"),
                )
                smtp.join(SERVER_JOIN_MS)

                assertTrue(received.any { it.startsWith("EHLO") }, "server saw: $received")
                assertTrue(received.none { it.startsWith("MAIL FROM") || it.startsWith("AUTH") }, "sent without TLS: $received")
                assertTrue(context.warnings.any { "Failed to send email output" in it && "STARTTLS" in it }, "${context.warnings}")
            }
        }

    @Test
    fun plaintextToALoopbackRelayDeliversTheMessage() =
        runTest {
            val received = Collections.synchronizedList(mutableListOf<String>())
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val smtp = thread(isDaemon = true) { serveRelay(server, received) }
                val context = TestPluginContext()
                val module = EmailOutputModule()
                assertTrue(module.initialize(context))
                module.outputTyped(
                    FormattedReport(formatId = "html", body = "<p>report</p>"),
                    EmailOutputConfig(
                        host = "127.0.0.1",
                        port = server.localPort,
                        from = "bot@example.com",
                        to = "team@example.com",
                        subject = "Relay check",
                        starttls = false,
                    ),
                )
                smtp.join(SERVER_JOIN_MS)

                assertTrue(received.any { it.startsWith("MAIL FROM:<bot@example.com>") }, "server saw: $received")
                assertTrue(received.any { it.startsWith("RCPT TO:<team@example.com>") }, "server saw: $received")
                assertTrue(received.any { it == "Subject: Relay check" }, "server saw: $received")
                assertTrue(received.any { "<p>report</p>" in it }, "server saw: $received")
                assertTrue(received.none { it.startsWith("STARTTLS") || it.startsWith("AUTH") }, "server saw: $received")
                assertTrue(context.warnings.isEmpty(), "${context.warnings}")
            }
        }

    /** Plain SMTP relay without STARTTLS or AUTH: records every line, including the DATA payload. */
    private fun serveRelay(
        server: ServerSocket,
        received: MutableList<String>,
    ) {
        server.accept().use { socket ->
            val out = socket.getOutputStream().bufferedWriter()
            val input = socket.getInputStream().bufferedReader()
            out.write("220 relay ESMTP\r\n")
            out.flush()
            var inData = false
            generateSequence { input.readLine() }.forEach { line ->
                received += line
                val reply = when {
                    inData && line == "." -> "250 queued\r\n".also { inData = false }
                    inData -> null
                    line.startsWith("EHLO") -> "250 relay\r\n"
                    line.startsWith("DATA") -> "354 go\r\n".also { inData = true }
                    line.startsWith("QUIT") -> "221 bye\r\n"
                    else -> "250 ok\r\n"
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
