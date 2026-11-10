package io.karandash.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TextRedactorTest {
    @Test
    fun `password equality comparison is not redacted`() {
        val samples = listOf(
            "if (canChangePassword == true) show()",
            "if (password == null) return",
            "val ok = token == other",
            "password: true",
            "secret = false",
            "token: null",
        )
        for (sample in samples) {
            assertEquals(sample, TextRedactor.redact(sample), "mangled '$sample'")
            assertEquals(sample, TextRedactor.redactForLog(sample), "mangled log '$sample'")
        }
        val quoted = """{"password": "null", "tokenEnabled": "true"}"""
        assertEquals(quoted, TextRedactor.redactForLog(quoted))
    }

    @Test
    fun `password assignment is still redacted`() {
        val plain = mapOf(
            "password=hunter2" to "password=[REDACTED]",
            "password: hunter2" to "password: [REDACTED]",
            "api_key = abc123" to "api_key = [REDACTED]",
            "secret=trueish" to "secret=[REDACTED]",
            "private const val WEATHER_API_KEY = \"9f8a7b\"" to "private const val WEATHER_API_KEY = [REDACTED]",
        )
        for ((sample, expected) in plain) {
            assertEquals(expected, TextRedactor.redact(sample), "leaked '$sample'")
        }
        val quoted = TextRedactor.redactForLog("""{"password": "x", "user": "bob"}""")
        assertFalse("\"x\"" in quoted, "leaked quoted value: $quoted")
        assertEquals("""{[REDACTED], "user": "bob"}""", quoted)
    }

    @Test
    fun `value stops at a bare quote so JSON stays valid`() {
        val samples = mapOf(
            "password='hunter 2' next" to "password=[REDACTED] next",
            """{"msg": "token=abc"}""" to """{"msg": "token=[REDACTED]"}""",
            """["password=x", "y"]""" to """["password=[REDACTED]", "y"]""",
            """{"url": "jdbc:pg://h/db?user=u&password=p"}""" to """{"url": "[REDACTED]"}""",
        )
        for ((sample, expected) in samples) {
            assertEquals(expected, TextRedactor.redact(sample), "mangled '$sample'")
        }
    }

    @Test
    fun `punctuation and escaped quotes in a value do not leak`() {
        val samples = mapOf(
            "password=pass,word9" to "password=[REDACTED]",
            "token=a;b)c]d}e" to "token=[REDACTED]",
            "f(token=abc)" to "f(token=[REDACTED]",
            """{"body": "password=\"x\" ok"}""" to """{"body": "password=[REDACTED] ok"}""",
            """{"body": "token=ab\"cd"}""" to """{"body": "token=[REDACTED]"}""",
        )
        for ((sample, expected) in samples) {
            assertEquals(expected, TextRedactor.redact(sample), "leaked '$sample'")
        }
    }

    @Test
    fun `secret starting with a literal followed by punctuation is redacted`() {
        for (sample in listOf("password=null-ish", "token=true-9f8a", "secret=false.key")) {
            assertFalse(sample.substringAfter('=') in TextRedactor.redact(sample), "leaked '$sample'")
        }
    }

    @Test
    fun `redacting twice equals redacting once`() {
        val samples = listOf(
            "guard let token = [REDACTED] else { return }",
            "SyncState(token: [REDACTED], enabled: x)",
            "password=hunter2 and token=abc]",
            "let token = tokens[0], ok",
        )
        for (sample in samples) {
            val once = TextRedactor.redact(sample)
            assertEquals(once, TextRedactor.redact(once), "not idempotent on '$sample'")
        }
    }

    @Test
    fun `unquoted code value is not redacted`() {
        val samples = listOf(
            "let token = deviceToken",
            "token = self.token",
            "SyncState(token: session.pushToken, enabled: x)",
            "guard let token = deviceToken else { return }",
        )
        for (sample in samples) {
            assertEquals(sample, TextRedactor.redact(sample), "mangled '$sample'")
        }
    }

    @Test
    fun `random key that looks like a name is still redacted`() {
        val samples = listOf(
            "api_key = xK9mP2qR7vT",
            "token = xKpQrTzWbN",
            "secret = abc.d3f.g4h",
            "token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig",
            "password=Hunter",
        )
        for (sample in samples) {
            assertFalse(sample.substringAfter('=').trim() in TextRedactor.redact(sample), "leaked '$sample'")
        }
    }
}
