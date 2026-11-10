package io.karandash.plugins.outputs

import io.karandash.testfixtures.TestFiles
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STARTTLS delivery verifies the server certificate: a fake SMTP server presents a self-signed certificate for
 * 127.0.0.1; a client that does not trust it must not send anything (no AUTH, no MAIL FROM), a client whose trust
 * store holds the certificate delivers. Each client runs in a child JVM ([SmtpClientMain]) because the JVM-wide
 * default trust store cannot change after first use.
 */
class EmailTlsVerificationTest {
    private val received = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var dir: File
    private lateinit var server: ServerSocket
    private lateinit var acceptor: Thread

    @BeforeTest
    fun setUp() {
        dir = java.nio.file.Paths
            .get(TestFiles.createTestTempDirectory("email-tls").toString())
            .toFile()
        keytool(
            "-genkeypair",
            "-alias",
            ALIAS,
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "2",
            "-dname",
            "CN=localhost",
            "-ext",
            "san=ip:127.0.0.1",
            "-keystore",
            "server.p12",
            "-storetype",
            "PKCS12",
        )
        keytool("-exportcert", "-alias", ALIAS, "-rfc", "-file", "server.pem", "-keystore", "server.p12")
        keytool(
            "-importcert",
            "-noprompt",
            "-alias",
            ALIAS,
            "-file",
            "server.pem",
            "-keystore",
            "trust.p12",
            "-storetype",
            "PKCS12",
        )
        val sslContext = loadServerContext()
        server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        acceptor = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                socket.use { serve(it, sslContext) }
            }
        }
    }

    @AfterTest
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    @Test
    fun untrustedCertificateIsRejectedAndNothingIsSent() {
        val (exit, output) = runClient(emptyList())

        assertEquals(1, exit, output)
        assertTrue(SmtpClientMain.FAILURE_PREFIX in output, output)
        assertTrue(received.none { it.startsWith("AUTH") || it.startsWith("MAIL FROM") }, "sent over untrusted TLS: $received")
    }

    @Test
    fun certificateInTheTrustStoreIsAcceptedAndMailIsDelivered() {
        val trust = File(dir, "trust.p12").path
        val (exit, output) = runClient(
            listOf(
                "-Djavax.net.ssl.trustStore=$trust",
                "-Djavax.net.ssl.trustStorePassword=$PASSWORD",
                "-Djavax.net.ssl.trustStoreType=PKCS12"
            ),
        )

        assertEquals(0, exit, output)
        assertTrue(received.any { it.startsWith("MAIL FROM") }, "server saw: $received")
        assertTrue(received.any { it == "Subject: tls check" }, "server saw: $received")
    }

    private fun runClient(jvmArgs: List<String>): Pair<Int, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            listOf(java) + jvmArgs +
                listOf("-cp", System.getProperty("java.class.path"), SmtpClientMain::class.java.name, server.localPort.toString()),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "client JVM timed out: $output" }
        return process.exitValue() to output
    }

    private fun keytool(vararg args: String) {
        val tool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(listOf(tool) + args + listOf("-storepass", PASSWORD))
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "keytool ${args.toList()} failed: $output" }
    }

    private fun loadServerContext(): SSLContext {
        val keyStore = KeyStore.getInstance("PKCS12")
        File(dir, "server.p12").inputStream().use { keyStore.load(it, PASSWORD.toCharArray()) }
        val keyManagers = KeyManagerFactory
            .getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, PASSWORD.toCharArray()) }
            .keyManagers
        return SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
    }

    /** Minimal ESMTP exchange: plain greeting, STARTTLS upgrade, then AUTH / MAIL / RCPT / DATA until QUIT. */
    private fun serve(
        plain: Socket,
        sslContext: SSLContext,
    ) {
        try {
            var reader = plain.getInputStream().bufferedReader()
            var writer = plain.getOutputStream().bufferedWriter()
            writer.reply("220 fake ESMTP")
            while (true) {
                val line = reader.readLine() ?: return
                received += line
                when {
                    line.startsWith("EHLO") -> {
                        writer.reply("250-fake\r\n250-STARTTLS\r\n250 AUTH PLAIN")
                    }

                    line == "STARTTLS" -> {
                        writer.reply("220 go ahead")
                        val tls = sslContext.socketFactory.createSocket(plain, null, plain.port, true) as SSLSocket
                        tls.useClientMode = false
                        tls.startHandshake()
                        reader = tls.getInputStream().bufferedReader()
                        writer = tls.getOutputStream().bufferedWriter()
                        serveSecure(reader, writer)
                        return
                    }

                    else -> {
                        writer.reply("250 ok")
                    }
                }
            }
        } catch (_: IOException) {
            // the client aborted the handshake or the connection: exactly what the untrusted case expects
        }
    }

    private fun serveSecure(
        reader: BufferedReader,
        writer: BufferedWriter,
    ) {
        while (true) {
            val line = reader.readLine() ?: return
            received += line
            when {
                line.startsWith("EHLO") -> {
                    writer.reply("250-fake\r\n250 AUTH PLAIN")
                }

                line == "AUTH PLAIN" -> {
                    writer.reply("334 ")
                    received += reader.readLine() ?: return
                    writer.reply("235 ok")
                }

                line.startsWith("AUTH") -> {
                    writer.reply("235 ok")
                }

                line == "DATA" -> {
                    writer.reply("354 go")
                    while (reader.readLine().also { received += it ?: "." } != ".") Unit
                    writer.reply("250 queued")
                }

                line == "QUIT" -> {
                    writer.reply("221 bye")
                    return
                }

                else -> {
                    writer.reply("250 ok")
                }
            }
        }
    }

    private fun BufferedWriter.reply(text: String) {
        write("$text\r\n")
        flush()
    }

    private companion object {
        const val ALIAS = "smtp"
        const val PASSWORD = "changeit"
        const val CLIENT_TIMEOUT_SECONDS = 60L
    }
}
