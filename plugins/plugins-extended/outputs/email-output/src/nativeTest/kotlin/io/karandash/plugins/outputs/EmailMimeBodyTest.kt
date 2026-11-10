package io.karandash.plugins.outputs

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertTrue

class EmailMimeBodyTest {
    @Test
    fun `without a text body the message is plain html`() {
        val body = mimeBody("<p>report</p>", null)

        assertTrue(body.startsWith("Content-Type: text/html; charset=utf-8\n\n<p>report</p>"), body)
    }

    @Test
    fun `with a text body the message is multipart alternative with the text first`() {
        val body = mimeBody("<p>report</p>", "report")

        assertTrue(body.startsWith("Content-Type: multipart/alternative;"), body)
        val text = Base64.Mime.encode("report".encodeToByteArray())
        val html = Base64.Mime.encode("<p>report</p>".encodeToByteArray())
        assertTrue(body.indexOf(text) in 0 until body.indexOf(html), body)
        assertTrue(body.trimEnd().endsWith("--"), body)
    }
}
