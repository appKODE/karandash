package io.karandash.plugins.outputs

import io.karandash.Version
import io.karandash.api.PluginContext
import io.karandash.api.ReporterConfig
import io.karandash.api.finding.FormattedReport
import io.karandash.helpers.output.NotifyWhen
import io.karandash.helpers.output.OnError
import io.karandash.helpers.output.handle
import io.karandash.helpers.reporter.ReportMediaKeys
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.sdk.typed.AbstractTypedOutputModule
import kotlinx.atomicfu.atomic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Config for [EmailOutputModule].
 *
 * @property host SMTP server hostname.
 * @property port SMTP server port (default 587 for STARTTLS).
 * @property username SMTP authentication username.
 * @property password SMTP authentication password.
 * @property to Recipient email address.
 * @property from Sender email address. Defaults to [username] when empty.
 * @property subject Email subject line. When null, the subject the format sets (`html`: `[karandash] FAILED · <MR title>`),
 *   else a generic validation result subject.
 * @property starttls Require STARTTLS (default). `false` sends plaintext and is accepted only for a loopback
 *   [host] (`localhost`, `127.0.0.1`, `::1`) - a local relay such as postfix; any other host is refused.
 * @property onError `warn` (default) only logs a failed send; `fail` also fails the output.
 * @property notifyWhen `when`: which runs deliver (`always`, `changed`: the verdict or the findings differ from the last
 *   platform summary, `failed`, `never`); default [NotifyWhen.CHANGED]. The pipeline applies it before calling the module.
 * @property language Language of the generic subject; injected from `report.language`.
 */
@Serializable
data class EmailOutputConfig(
    val host: String = "",
    val port: Int = DEFAULT_SMTP_PORT,
    val username: String? = null,
    val password: String? = null,
    val to: String = "",
    val from: String? = null,
    val subject: String? = null,
    val starttls: Boolean = true,
    val onError: OnError = OnError.WARN,
    @SerialName("when")
    val notifyWhen: NotifyWhen = NotifyWhen.CHANGED,
    val language: String? = null,
) : ReporterConfig {
    companion object {
        internal const val DEFAULT_SMTP_PORT = 587
        internal val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")
    }
}

/**
 * Output module that sends a [FormattedReport.body] as an HTML email via SMTP.
 *
 * Accepts only the `"html"` format - expects [FormattedReport.body] to be valid HTML.
 * Uses platform-specific SMTP: Jakarta Mail on JVM, curl on native targets.
 */
class EmailOutputModule : AbstractTypedOutputModule<EmailOutputConfig>(EmailOutputConfig.serializer()) {
    override val id: String = ID
    override val name: String = "Email Output"
    override val version: String = Version.VERSION
    override val outputId: String = OUTPUT_ID
    override val acceptedFormats: Set<String> = setOf("html")
    override val preferredFormat: String = "html"
    override val description: String = "Sends an HTML report body as an email via SMTP."

    companion object {
        /** Module ID for registry lookups. */
        const val ID = "io.karandash.output.email"

        /** Output identifier. */
        const val OUTPUT_ID = "email"

        /** The configured subject, else the one the format set, else the generic subject in [EmailOutputConfig.language]. */
        internal fun subjectOf(
            config: EmailOutputConfig,
            report: FormattedReport,
        ): String =
            config.subject?.takeIf { it.isNotEmpty() }
                ?: report.metadata[ReportMediaKeys.SUBJECT]?.takeIf { it.isNotEmpty() }
                ?: EmailText.DEFAULT_SUBJECT.of(config.language)
    }

    private var logger: Logger = NoOpLogger
    private val initialized = atomic(false)

    override suspend fun initialize(context: PluginContext): Boolean {
        if (!EmailOutputPlatformSupport.isEmailSendingSupported()) {
            context.warn("Email output is not supported on this platform")
            return false
        }
        logger = context.logger
        initialized.value = true
        logger.info("Email output module initialized")
        return true
    }

    override suspend fun outputTyped(
        report: FormattedReport,
        config: EmailOutputConfig,
    ) {
        if (!initialized.value) return

        config.onError.handle(logger, "Failed to send email output") {
            require(config.host.isNotEmpty()) { "Missing 'host' in email output config" }
            require(config.to.isNotEmpty()) { "Missing 'to' in email output config" }
            require(config.starttls || config.host.lowercase() in EmailOutputConfig.LOOPBACK_HOSTS) {
                "Email 'starttls: false' is only allowed for a loopback SMTP host (localhost, 127.0.0.1, ::1); " +
                    "got '${config.host}'"
            }

            val effectiveFrom = config.from?.takeIf { it.isNotEmpty() }
                ?: config.username?.takeIf { it.isNotEmpty() }
                ?: error("Email output requires 'from' or 'username' to derive sender address")

            val subject = subjectOf(config, report)
            // A CR/LF would let the value inject extra SMTP headers (Bcc:, ...) into the message.
            listOf("to" to config.to, "from" to effectiveFrom, "subject" to subject).forEach { (field, value) ->
                require('\r' !in value && '\n' !in value) { "Email '$field' must not contain CR or LF" }
            }

            val client = EmailOutputClient(
                host = config.host,
                port = config.port,
                username = config.username ?: "",
                password = config.password ?: "",
                from = effectiveFrom,
                useTls = config.starttls,
            )

            client.sendEmail(
                to = config.to,
                subject = subject,
                htmlBody = report.body,
                textBody = report.metadata[ReportMediaKeys.TEXT_BODY],
            )

            logger.info("Email output sent successfully to ${config.to}")
        }
    }

    override suspend fun shutdown() {
        if (initialized.value) {
            logger.info("Email output module shutting down")
        }
    }
}
