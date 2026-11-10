package io.karandash.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TicketContentRedactorTest {
    @Test
    fun emptyStringReturnsEmpty() {
        assertEquals("", TicketContentRedactor.redact(""))
    }

    @Test
    fun plainTextPassesThrough() {
        val text = "This MR fixes the login bug."
        assertEquals(text, TicketContentRedactor.redact(text))
    }

    @Test
    fun redactsEmail() {
        val result = TicketContentRedactor.redact("Contact alice@example.com for review.")
        assertFalse(result.contains("alice@example.com"))
        assertTrue(result.contains("[PII:email]"))
    }

    @Test
    fun redactsE164Phone() {
        val result = TicketContentRedactor.redact("Call +12025551234 now.")
        assertFalse(result.contains("+12025551234"))
        assertTrue(result.contains("[PII:phone]"))
    }

    @Test
    fun redactsUsPhone() {
        val result = TicketContentRedactor.redact("Phone: 555-123-4567")
        assertFalse(result.contains("555-123-4567"))
        assertTrue(result.contains("[PII:phone]"))
    }

    @Test
    fun redactsSsnLike() {
        val result = TicketContentRedactor.redact("SSN: 123-45-6789")
        assertFalse(result.contains("123-45-6789"))
        assertTrue(result.contains("[PII:ssn]"))
    }

    @Test
    fun redactsCreditCardLike() {
        val result = TicketContentRedactor.redact("Card: 4111 1111 1111 1111")
        assertFalse(result.contains("4111 1111 1111 1111"))
        assertTrue(result.contains("[PII:cc]"))
    }

    @Test
    fun redactsJiraMention() {
        val result = TicketContentRedactor.redact("Assigned to @alice.bob for review.")
        assertFalse(result.contains("@alice.bob"))
        assertTrue(result.contains("@user"))
    }

    @Test
    fun redactsShellExport() {
        val result = TicketContentRedactor.redact("export SECRET_TOKEN=abc123xyz")
        assertFalse(result.contains("abc123xyz"))
    }

    @Test
    fun redactsConnectionString() {
        val result = TicketContentRedactor.redact("Connect via postgres://user:pass@host/db")
        assertFalse(result.contains("postgres://user:pass@host/db"))
        assertTrue(result.contains("[REDACTED:connection-string]"))
    }

    @Test
    fun redactsAuthHeader() {
        val result = TicketContentRedactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9")
        assertFalse(result.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun doesNotRedactTicketKey() {
        // Ticket keys like ABC-123 must NOT be mistaken for phone/SSN
        val result = TicketContentRedactor.redact("Fixes PROJ-123 and ACME-4567.")
        assertTrue(result.contains("PROJ-123"))
        assertTrue(result.contains("ACME-4567"))
    }

    @Test
    fun doesNotRedactVersionNumbers() {
        val result = TicketContentRedactor.redact("Upgrade to version 1.2.3-beta")
        assertTrue(result.contains("1.2.3-beta"))
    }

    @Test
    fun redactsMultiplePiiInOneLine() {
        val result = TicketContentRedactor.redact("alice@example.com called 555-123-4567")
        assertFalse(result.contains("alice@example.com"))
        assertFalse(result.contains("555-123-4567"))
    }

    @Test
    fun keepsLongSourcePathButRedactsLongToken() {
        val path = "plugins/plugins-ai/ai-core/src/commonMain/kotlin/io/karandash/Foo.kt"
        assertTrue(TicketContentRedactor.redact("see $path here").contains(path))
        val token = "QWxhZGRpbjpvcGVuIHNlc2FtZQ/QWxhZGRpbjpvcGVuIHNlc2FtZQ"
        assertFalse(TicketContentRedactor.redact("key $token end").contains(token))
    }

    @Test
    fun `a commit SHA and a code annotation survive while an encoded blob is redacted`() {
        val sha1 = "3f786850e387550fdab836ed7e6dc881de23001b"
        val sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        val blob = "c2VjcmV0LXRva2VuLWZvci10ZXN0aW5nLW9ubHktMTIzNDU2Nzg5MA"
        val result = TicketContentRedactor.redact("Fixed in $sha1 and $sha256 with @Override; leaked $blob")

        assertTrue(result.contains(sha1), result)
        assertTrue(result.contains(sha256), result)
        assertTrue(result.contains("@Override"), result)
        assertFalse(result.contains(blob), result)
    }
}
