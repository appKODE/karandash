package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** `starttls: false` is refused for a non-loopback host before any SMTP connection is made. */
class EmailPlaintextPolicyTest {
    @Test
    fun plaintextToARemoteHostIsRefused() =
        runTest {
            listOf("smtp.invalid", "10.0.0.5", "127.0.0.1.nip.io").forEach { host ->
                val context = TestPluginContext()
                val module = EmailOutputModule()
                if (!module.initialize(context)) return@runTest
                module.outputTyped(
                    FormattedReport(formatId = "html", body = "<p>report</p>"),
                    EmailOutputConfig(host = host, from = "bot@example.com", to = "team@example.com", starttls = false),
                )
                assertTrue(
                    context.warnings.any { "'starttls: false' is only allowed for a loopback SMTP host" in it && host in it },
                    "plaintext to $host not refused: ${context.warnings}",
                )
            }
        }
}
