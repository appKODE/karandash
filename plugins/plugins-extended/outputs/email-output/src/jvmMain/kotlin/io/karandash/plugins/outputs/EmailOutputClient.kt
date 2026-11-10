package io.karandash.plugins.outputs

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.util.Date
import java.util.Properties

actual class EmailOutputClient actual constructor(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val from: String,
    private val useTls: Boolean,
) {
    private companion object {
        private const val SMTP_TIMEOUT_MS = 10_000
        private const val SMTPS_PORT = 465
    }

    actual fun sendEmail(
        to: String,
        subject: String,
        htmlBody: String,
        textBody: String?,
    ) {
        val recipient = to
        val props = Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", username.isNotEmpty().toString())

            if (port == SMTPS_PORT) {
                // Port 465 is implicit TLS (as the native client's smtps://), not STARTTLS.
                put("mail.smtp.ssl.enable", "true")
            } else if (useTls) {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }

            put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
            put("mail.smtp.ssl.checkserveridentity", "true")

            put("mail.smtp.connectiontimeout", SMTP_TIMEOUT_MS.toString())
            put("mail.smtp.timeout", SMTP_TIMEOUT_MS.toString())
            put("mail.smtp.writetimeout", SMTP_TIMEOUT_MS.toString())
        }

        val session = Session.getInstance(
            props,
            object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication =
                    PasswordAuthentication(
                        username,
                        password,
                    )
            },
        )

        val senderAddress = from
        val recipientAddress = recipient
        val emailSubject = subject
        val emailBody = htmlBody
        try {
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(senderAddress))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientAddress))
                setSubject(emailSubject, "UTF-8")
                if (textBody == null) {
                    setContent(emailBody, "text/html; charset=utf-8")
                } else {
                    val text = MimeBodyPart().apply { setText(textBody, "UTF-8") }
                    val html = MimeBodyPart().apply { setContent(emailBody, "text/html; charset=utf-8") }
                    setContent(MimeMultipart("alternative", text, html))
                }
                sentDate = Date()
            }
            Transport.send(message)
        } catch (e: MessagingException) {
            error("Failed to send email: ${e.message}")
        }
    }
}
