package io.karandash.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SsrfUrlValidatorTest {
    @Test
    fun allowLocalhostTruePermitsLocalhostWithPort() {
        SsrfUrlValidator.validate("http://localhost:8080/webhook", allowLocalhost = true)
    }

    @Test
    fun allowLocalhostTruePermitsLoopbackIpWithPort() {
        SsrfUrlValidator.validate("http://127.0.0.1:3000/hook", allowLocalhost = true)
    }

    @Test
    fun allowLocalhostTruePermitsPrivateNetworkIp() {
        SsrfUrlValidator.validate("http://192.168.1.1/api", allowLocalhost = true)
    }

    @Test
    fun allowLocalhostTrueRejectsFtpScheme() {
        val ex = assertFailsWith<IllegalArgumentException> {
            SsrfUrlValidator.validate("ftp://localhost/path", allowLocalhost = true)
        }
        assertTrue(ex.message!!.contains("HTTP or HTTPS"), "Expected HTTP/HTTPS protocol error, got: ${ex.message}")
    }

    @Test
    fun allowLocalhostFalseBlocksLocalhostByDefault() {
        val ex = assertFailsWith<IllegalArgumentException> {
            SsrfUrlValidator.validate("https://localhost/webhook")
        }
        assertTrue(
            ex.message!!.contains("blocked host") || ex.message!!.contains("SSRF"),
            "Expected SSRF block message, got: ${ex.message}"
        )
    }

    @Test
    fun allowLocalhostFalseBlocksLoopbackIp() {
        val ex = assertFailsWith<IllegalArgumentException> {
            SsrfUrlValidator.validate("https://127.0.0.1/hook")
        }
        assertTrue(
            ex.message!!.contains("blocked host") || ex.message!!.contains("SSRF"),
            "Expected SSRF block message, got: ${ex.message}"
        )
    }

    @Test
    fun allowLocalhostFalseBlocksPrivateNetworkIp() {
        val ex = assertFailsWith<IllegalArgumentException> {
            SsrfUrlValidator.validate("https://192.168.1.1/api")
        }
        assertTrue(
            ex.message!!.contains("private IP") || ex.message!!.contains("SSRF"),
            "Expected private IP block message, got: ${ex.message}"
        )
    }

    // ── Bug 7: hostname without dots ─────────────────────────────────────

    @Test
    fun rejectsHostnameWithoutDots() {
        val ex = assertFailsWith<IllegalArgumentException> {
            SsrfUrlValidator.validate("https://internalhost/webhook")
        }
        assertTrue(
            ex.message!!.contains("without dots") || ex.message!!.contains("suspicious"),
            "Expected hostname-without-dots message, got: ${ex.message}"
        )
    }

    @Test
    fun acceptsPublicHostnameWithDots() {
        // example.com is in the reserved test domain allowlist
        SsrfUrlValidator.validate("https://hooks.example.com/webhook")
    }

    @Test
    fun acceptsARealPublicHostname() {
        SsrfUrlValidator.validate("https://hooks.slack.com/services/T000/B000/XXX")
        assertEquals("https://api.openai.com/v1", SsrfUrlValidator.validateBaseUrl("https://api.openai.com/v1/"))
    }

    @Test
    fun rejectsLoopbackSpelledAnotherWay() {
        val spellings = listOf(
            "https://127.1/x",
            "https://localhost./x",
            "https://127.0.0.1./x",
            "https://LOCALHOST/x",
            "https://2130706433/x",
            "https://0x7f.0.0.1/x",
            "https://0177.0.0.1/x",
            "https://user@localhost/x",
            "https://[::ffff:127.0.0.1]/x",
        )
        for (url in spellings) {
            assertFailsWith<IllegalArgumentException>(url) { SsrfUrlValidator.validate(url) }
            assertFailsWith<IllegalArgumentException>(url) { SsrfUrlValidator.validateBaseUrl(url) }
        }
    }

    @Test
    fun baseUrlAllowsDotlessHostsButNotPrivateRanges() {
        SsrfUrlValidator.validateBaseUrl("https://ollama:11434")
        assertFailsWith<IllegalArgumentException> { SsrfUrlValidator.validateBaseUrl("https://10.0.0.5/v1") }
        SsrfUrlValidator.validateBaseUrl("http://127.0.0.1:8080/v1", allowLocalhost = true)
    }
}
