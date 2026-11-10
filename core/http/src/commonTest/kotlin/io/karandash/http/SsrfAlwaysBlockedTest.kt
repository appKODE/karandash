package io.karandash.http

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Metadata and link-local hosts stay blocked under `allow_localhost`, operator-added
 * blocked hosts are refused, and a base-URL match needs a path boundary.
 */
class SsrfAlwaysBlockedTest {
    @AfterTest
    fun reset() = SsrfUrlValidator.configureAdditionalBlockedHosts(emptySet())

    private val alwaysBlocked = listOf(
        "http://169.254.169.254/latest/meta-data",
        "http://metadata.google.internal/computeMetadata",
        "http://169.254.10.1/x",
        "http://[fe80::1]/x",
        "http://[fd00:ec2::254]/x",
    )

    @Test
    fun `validate under allowLocalhost still rejects metadata and link-local hosts`() {
        alwaysBlocked.forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) { SsrfUrlValidator.validate(url, allowLocalhost = true) }
        }
        SsrfUrlValidator.validate("http://localhost:8080/hook", allowLocalhost = true)
    }

    @Test
    fun `validateBaseUrl under allowLocalhost still rejects metadata and link-local hosts`() {
        alwaysBlocked.forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) { SsrfUrlValidator.validateBaseUrl(url, allowLocalhost = true) }
        }
        SsrfUrlValidator.validateBaseUrl("http://127.0.0.1:8080/v1", allowLocalhost = true)
    }

    @Test
    fun `additional blocked hosts are refused`() {
        SsrfUrlValidator.configureAdditionalBlockedHosts(setOf("proxy.corp.example.com"))
        assertFailsWith<IllegalArgumentException> { SsrfUrlValidator.validate("https://proxy.corp.example.com/x") }
        assertFailsWith<IllegalArgumentException> {
            SsrfUrlValidator.validate("http://proxy.corp.example.com/x", allowLocalhost = true)
        }
    }

    @Test
    fun `validateUrlMatchesBase needs a path boundary`() {
        val base = "https://api.bitbucket.org/2.0"
        SsrfUrlValidator.validateUrlMatchesBase("https://api.bitbucket.org/2.0/repositories?page=2", base)
        SsrfUrlValidator.validateUrlMatchesBase("https://api.bitbucket.org/2.0", base)
        assertFailsWith<IllegalArgumentException> {
            SsrfUrlValidator.validateUrlMatchesBase("https://api.bitbucket.org/2.0.evil.com/x", base)
        }
        assertFailsWith<IllegalArgumentException> {
            SsrfUrlValidator.validateUrlMatchesBase("https://api.bitbucket.org.evil.com/2.0/x", "https://api.bitbucket.org")
        }
    }
}
