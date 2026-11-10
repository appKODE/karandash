package io.karandash.plugins.outputs

import io.karandash.platform.PlatformProcess
import io.karandash.platform.createTempFileInDir
import io.karandash.platform.deleteIfExists
import io.karandash.platform.tempDirectory
import io.karandash.platform.writeText
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.format
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant

actual class EmailOutputClient actual constructor(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val from: String,
    private val useTls: Boolean,
) {
    actual fun sendEmail(
        to: String,
        subject: String,
        htmlBody: String,
        textBody: String?,
    ) {
        val mimeMessage = buildMimeMessage(to, subject, htmlBody, textBody)
        val configFile = if (username.isNotEmpty()) createTempFileInDir(tempDirectory(), "karandash-curl-", ".cfg") else null
        try {
            // The credentials go in an owner-only curl config file, so they never show in the process list.
            configFile?.writeText(curlUserConfig(username, password))
            val result = PlatformProcess.execute(
                command = curlCommand(CurlMail(buildSmtpUrl(), from, to, useTls), configFile?.toString()),
                workingDir = null,
                stdin = mimeMessage,
            )
            if (result.isFailed) {
                val errorDetail = result.error.ifEmpty { result.output }.ifEmpty { "exit code ${result.exitCode}" }
                error("Failed to send email via curl: $errorDetail")
            }
        } finally {
            configFile?.let(::deleteIfExists)
        }
    }

    private fun buildSmtpUrl(): String {
        val scheme = if (port == 465) "smtps" else "smtp"
        return "$scheme://$host:$port"
    }

    private fun buildMimeMessage(
        to: String,
        subject: String,
        htmlBody: String,
        textBody: String?,
    ): String =
        buildString {
            appendLine("From: $from")
            appendLine("To: $to")
            appendLine("Subject: ${mimeSubject(subject)}")
            appendLine("Date: ${mimeDate(Clock.System.now())}")
            appendLine("MIME-Version: 1.0")
            append(mimeBody(htmlBody, textBody))
        }
}

/** Separates the parts of a `multipart/alternative` body; the parts are base64, so it can never occur in them. */
private const val MIME_BOUNDARY = "karandash-alternative-boundary"

/**
 * The content headers and body: [htmlBody] alone as `text/html`, or with [textBody] a `multipart/alternative` of the
 * plain text then the HTML, each part base64-encoded.
 */
internal fun mimeBody(
    htmlBody: String,
    textBody: String?,
): String =
    buildString {
        if (textBody == null) {
            appendLine("Content-Type: text/html; charset=utf-8")
            appendLine()
            append(htmlBody)
            return@buildString
        }
        appendLine("Content-Type: multipart/alternative; boundary=\"$MIME_BOUNDARY\"")
        appendLine()
        for ((type, content) in listOf("text/plain" to textBody, "text/html" to htmlBody)) {
            appendLine("--$MIME_BOUNDARY")
            appendLine("Content-Type: $type; charset=utf-8")
            appendLine("Content-Transfer-Encoding: base64")
            appendLine()
            appendLine(Base64.Mime.encode(content.encodeToByteArray()))
        }
        appendLine("--$MIME_BOUNDARY--")
    }

/** One curl SMTP send, without credentials. */
@Serializable
internal data class CurlMail(
    val smtpUrl: String,
    val from: String,
    val to: String,
    val useTls: Boolean,
)

/** The curl argv for [mail]; credentials come only from the `-K` [configPath], never from argv. */
internal fun curlCommand(
    mail: CurlMail,
    configPath: String?,
): List<String> =
    buildList {
        addAll(listOf("curl", "--url", mail.smtpUrl, "--mail-from", mail.from))
        mail.to
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { addAll(listOf("--mail-rcpt", it)) }
        addAll(listOf("--upload-file", "-", "--silent", "--show-error", "--max-time", CURL_MAX_TIME_SECONDS))
        if (configPath != null) addAll(listOf("-K", configPath))
        if (mail.useTls) add("--ssl-reqd")
    }

/** Whole-send cap for curl, so a stalled SMTP server cannot hang the run. */
private const val CURL_MAX_TIME_SECONDS = "60"

/** [subject] as an RFC 2047 `B` encoded word when it is not printable ASCII, else as is. */
internal fun mimeSubject(subject: String): String =
    if (subject.all { it in ' '..'~' }) {
        subject
    } else {
        // limit: one encoded word, may pass the 75-char RFC 2047 limit; split per word if a server rejects it
        "=?UTF-8?B?${Base64.encode(subject.encodeToByteArray())}?="
    }

/** [now] as an RFC 5322 `Date` header value, in UTC. */
internal fun mimeDate(now: Instant): String = DateTimeComponents.Formats.RFC_1123.format { setDateTimeOffset(now, UtcOffset.ZERO) }

/** A curl config line setting `user`, quoted per curl's config file syntax. */
internal fun curlUserConfig(
    username: String,
    password: String,
): String {
    val quoted = "$username:$password"
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    return "user = \"$quoted\"\n"
}
