package io.karandash.providers.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UrlValidatorTest {
    // allowLocalhost = true: allowed addresses

    @Test
    fun `validateBaseUrl allowLocalhost true allows localhost with port`() {
        val result = UrlValidator.validateBaseUrl("http://localhost:8080", allowLocalhost = true)

        assertEquals("http://localhost:8080", result)
    }

    @Test
    fun `validateBaseUrl allowLocalhost true allows 127 dot 0 dot 0 dot 1`() {
        val result = UrlValidator.validateBaseUrl("http://127.0.0.1:3000", allowLocalhost = true)

        assertEquals("http://127.0.0.1:3000", result)
    }

    @Test
    fun `validateBaseUrl allowLocalhost true allows 192 dot 168 range`() {
        val result = UrlValidator.validateBaseUrl("http://192.168.1.1", allowLocalhost = true)

        assertEquals("http://192.168.1.1", result)
    }

    @Test
    fun `validateBaseUrl allowLocalhost true allows 10 dot 0 range`() {
        val result = UrlValidator.validateBaseUrl("http://10.0.0.1", allowLocalhost = true)

        assertEquals("http://10.0.0.1", result)
    }

    // allowLocalhost = true: blank URL is still rejected

    @Test
    fun `validateBaseUrl allowLocalhost true rejects blank URL`() {
        assertFailsWith<IllegalArgumentException> {
            UrlValidator.validateBaseUrl("   ", allowLocalhost = true)
        }
    }

    @Test
    fun `validateBaseUrl allowLocalhost true rejects empty URL`() {
        assertFailsWith<IllegalArgumentException> {
            UrlValidator.validateBaseUrl("", allowLocalhost = true)
        }
    }

    // allowLocalhost = true: scheme is still enforced

    @Test
    fun `validateBaseUrl allowLocalhost true rejects ftp scheme`() {
        assertFailsWith<IllegalArgumentException> {
            UrlValidator.validateBaseUrl("ftp://localhost:21", allowLocalhost = true)
        }
    }

    @Test
    fun `validateBaseUrl allowLocalhost true rejects URL with no scheme`() {
        assertFailsWith<IllegalArgumentException> {
            UrlValidator.validateBaseUrl("localhost:8080", allowLocalhost = true)
        }
    }

    @Test
    fun `validateBaseUrl allowLocalhost true accepts https scheme`() {
        val result = UrlValidator.validateBaseUrl("https://localhost:8443", allowLocalhost = true)

        assertEquals("https://localhost:8443", result)
    }

    // allowLocalhost = false (default): private addresses are blocked

    @Test
    fun `validateBaseUrl default blocks localhost`() {
        assertFailsWith<IllegalArgumentException> {
            UrlValidator.validateBaseUrl("https://localhost:8080")
        }
    }

    @Test
    fun `validateBaseUrl default blocks 127 dot 0 dot 0 dot 1`() {
        assertFailsWith<IllegalArgumentException> {
            UrlValidator.validateBaseUrl("https://127.0.0.1")
        }
    }

    @Test
    fun `validateBaseUrl default blocks 192 dot 168 range`() {
        assertFailsWith<IllegalArgumentException> {
            UrlValidator.validateBaseUrl("https://192.168.1.1")
        }
    }

    @Test
    fun `validateBaseUrl default blocks 10 dot 0 range`() {
        assertFailsWith<IllegalArgumentException> {
            UrlValidator.validateBaseUrl("https://10.0.0.1")
        }
    }
}
