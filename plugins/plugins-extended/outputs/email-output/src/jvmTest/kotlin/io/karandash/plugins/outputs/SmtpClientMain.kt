package io.karandash.plugins.outputs

import kotlin.system.exitProcess

/**
 * Child-JVM entry point for [EmailTlsVerificationTest]: sends one email through [EmailOutputClient] to the
 * loopback SMTP server on the port in `args[0]`. Exit code 0 = delivered, 1 = failed (reason on stdout).
 * It runs in its own JVM because the JVM-wide default trust store is fixed on first TLS use.
 */
object SmtpClientMain {
    const val FAILURE_PREFIX = "SEND FAILED: "
    private const val FAILURE_EXIT_CODE = 1

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            EmailOutputClient("127.0.0.1", args.single().toInt(), "bot@example.com", "secret", "bot@example.com", useTls = true)
                .sendEmail("team@example.com", "tls check", "<p>report</p>")
        } catch (e: IllegalStateException) {
            println("$FAILURE_PREFIX${e.message}")
            exitProcess(FAILURE_EXIT_CODE)
        }
    }
}
