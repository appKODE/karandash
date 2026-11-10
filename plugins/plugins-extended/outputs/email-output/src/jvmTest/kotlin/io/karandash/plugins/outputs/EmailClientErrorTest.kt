package io.karandash.plugins.outputs

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmailClientErrorTest {
    @Test
    fun `implicit tls html only send to an unreachable relay fails with a descriptive error`() {
        val client = EmailOutputClient("127.0.0.1", SMTPS_PORT, "", "", "bot@example.com", true)
        val error = assertFailsWith<IllegalStateException> { client.sendEmail("team@example.com", "s", "<p>x</p>", null) }
        assertTrue(error.message.orEmpty().startsWith("Failed to send email"), error.message)
    }

    @Test
    fun `multipart send to an unreachable relay fails with a descriptive error`() {
        val client = EmailOutputClient("127.0.0.1", SMTPS_PORT, "", "", "bot@example.com", true)
        val error = assertFailsWith<IllegalStateException> { client.sendEmail("team@example.com", "s", "<p>x</p>", "x") }
        assertTrue(error.message.orEmpty().startsWith("Failed to send email"), error.message)
    }

    private companion object {
        const val SMTPS_PORT = 465
    }
}
