package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** sec: CR/LF in the subject or recipients is rejected before any SMTP connection is made. */
class EmailHeaderInjectionTest {
    private suspend fun warningsFor(config: EmailOutputConfig): List<String>? {
        val context = TestPluginContext()
        val module = EmailOutputModule()
        if (!module.initialize(context)) return null
        module.outputTyped(FormattedReport(formatId = "html", body = "<p>report</p>"), config)
        return context.warnings
    }

    private val base = EmailOutputConfig(host = "smtp.invalid", username = "bot@example.com", to = "team@example.com")

    @Test
    fun crlfInSubjectOrRecipientsIsRejected() =
        runTest {
            listOf(
                "subject" to base.copy(subject = "Report\r\nBcc: attacker@example.com"),
                "subject" to base.copy(subject = "Report\nBcc: attacker@example.com"),
                "to" to base.copy(to = "team@example.com\r\nBcc: attacker@example.com"),
                "from" to base.copy(from = "bot@example.com\nBcc: attacker@example.com"),
            ).forEach { (field, config) ->
                val warnings = warningsFor(config) ?: return@runTest
                assertTrue(
                    warnings.any { "'$field' must not contain CR or LF" in it },
                    "injection in $field not rejected: $warnings",
                )
            }
        }

    @Test
    fun missingFieldsAreWarnedBeforeAnySmtpConnection() =
        runTest {
            val cases =
                listOf(
                    base.copy(host = "") to "Missing 'host' in email output config",
                    base.copy(to = "") to "Missing 'to' in email output config",
                    base.copy(from = "", username = "") to "Email output requires 'from' or 'username' to derive sender address",
                    base.copy(from = "", subject = "", to = "a@example.com\nb") to "Email 'to' must not contain CR or LF",
                )
            for ((config, warning) in cases) {
                val warnings = warningsFor(config) ?: return@runTest
                assertEquals(listOf("Failed to send email output: $warning"), warnings)
            }
        }

    @Test
    fun fullJsonConfigIsDecodedAndUninitializedModuleSendsNothing() =
        runTest {
            val context = TestPluginContext()
            val module = EmailOutputModule()
            val report = FormattedReport(formatId = "html", body = "<p>report</p>")
            val config =
                """{"host": "smtp.invalid", "port": 25, "username": "u", "password": "p", "to": "x\ny",
                   "from": "f@example.com", "subject": "s", "starttls": true}"""

            module.output(report, Json.parseToJsonElement(config))
            assertTrue(context.warnings.isEmpty())

            if (!module.initialize(context)) return@runTest
            module.output(report, Json.parseToJsonElement(config))
            assertEquals(listOf("Failed to send email output: Email 'to' must not contain CR or LF"), context.warnings)
        }
}
