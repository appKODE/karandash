package io.karandash.plugins.outputs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** the SMTP password reaches curl through a 0600 `-K` config file, never through argv. */
class EmailCurlCredentialsTest {
    @Test
    fun `the curl argv carries the config file and not the password`() {
        val argv = curlCommand(CurlMail("smtp://h:25", "a@x", "b@x", useTls = true), configPath = "/tmp/k.cfg")
        assertFalse(argv.any { "s3cret" in it || it == "--user" }, argv.toString())
        assertEquals("/tmp/k.cfg", argv[argv.indexOf("-K") + 1])
        assertTrue("--ssl-reqd" in argv)
        assertFalse("-K" in curlCommand(CurlMail("smtp://h:25", "a@x", "b@x", useTls = false), configPath = null))
    }

    @Test
    fun `every recipient gets its own mail-rcpt and the send has a time cap`() {
        val argv = curlCommand(CurlMail("smtp://h:25", "a@x", "b@x, c@x,", useTls = false), configPath = null)
        assertEquals(listOf("b@x", "c@x"), argv.indices.filter { argv[it] == "--mail-rcpt" }.map { argv[it + 1] })
        assertEquals("60", argv[argv.indexOf("--max-time") + 1])
    }

    @Test
    fun `a non-ASCII subject is RFC 2047 encoded and an ASCII one is kept`() {
        assertEquals("Report ok", mimeSubject("Report ok"))
        assertEquals("=?UTF-8?B?0J7RgtGH0ZHRgg==?=", mimeSubject("Отчёт"))
    }

    @Test
    fun `the Date header is RFC 5322 in UTC`() {
        assertEquals("Thu, 29 Oct 2026 10:00:00 GMT", mimeDate(Instant.parse("2026-10-29T10:00:00Z")))
    }

    @Test
    fun `the curl config quotes the credentials`() {
        assertEquals("user = \"me:p\\\"w\\\\d\\n\"\n", curlUserConfig("me", "p\"w\\d\n"))
    }
}
