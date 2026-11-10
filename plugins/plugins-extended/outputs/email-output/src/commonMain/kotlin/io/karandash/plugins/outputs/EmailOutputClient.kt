package io.karandash.plugins.outputs

/**
 * Platform-specific SMTP client for sending HTML email; with a `textBody`, the message is `multipart/alternative`
 * with the plain text first and the HTML second.
 *
 * JVM: uses Jakarta Mail. Native: uses curl subprocess.
 */
expect class EmailOutputClient(
    host: String,
    port: Int,
    username: String,
    password: String,
    from: String,
    useTls: Boolean,
) {
    fun sendEmail(
        to: String,
        subject: String,
        htmlBody: String,
        textBody: String? = null,
    )
}
