package io.karandash.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class UrlSanitizerTest {
    /** Every token-like query or fragment parameter and any userinfo is stripped. */
    @Test
    fun `token-like params and userinfo are stripped`() {
        val names = listOf(
            "private_token",
            "access_token",
            "token",
            "key",
            "api_key",
            "apikey",
            "sig",
            "signature",
            "X-Amz-Signature",
            "client_secret",
            "password",
            "auth",
            "code",
            "jwt",
        )
        names.forEach { name ->
            assertEquals("https://h/p?$name=[REDACTED]&page=2", UrlSanitizer.sanitize("https://h/p?$name=v&page=2"), name)
            assertEquals("https://h/p#$name=[REDACTED]", UrlSanitizer.sanitize("https://h/p#$name=v"), name)
        }
        assertEquals("https://h:8443/p", UrlSanitizer.sanitize("https://u:p@ss@h:8443/p"))
        assertEquals("https://h/p", UrlSanitizer.sanitize("https://tokenonly@h/p"))
    }

    @Test
    fun stripsUserinfo() {
        assertEquals(
            "https://config.example.com:8443/base.yaml",
            UrlSanitizer.sanitize("https://alice:s3cret@config.example.com:8443/base.yaml"),
        )
    }

    @Test
    fun redactsSensitiveQueryAndFragmentValuesOnly() {
        assertEquals(
            "https://h/p?private_token=[REDACTED]&page=2&X-Amz-Signature=[REDACTED]#access_token=[REDACTED]",
            UrlSanitizer.sanitize("https://h/p?private_token=abc&page=2&X-Amz-Signature=def#access_token=ghi"),
        )
    }

    @Test
    fun redactsWebhookPaths() {
        assertEquals(
            "https://hooks.slack.com/services/[REDACTED]",
            UrlSanitizer.sanitize("https://hooks.slack.com/services/T0/B0/XYZ"),
        )
        assertEquals(
            "https://discord.com/api/webhooks/[REDACTED]",
            UrlSanitizer.sanitize("https://discord.com/api/webhooks/1/tok"),
        )
    }

    @Test
    fun leavesOrdinaryUrlsAndTextUnchanged() {
        val url = "https://gitlab.com/api/v4/projects/group%2Frepo/merge_requests/42?page=1"
        assertEquals(url, UrlSanitizer.sanitize(url))
        assertEquals("no url here", UrlSanitizer.sanitizeText("no url here"))
    }

    @Test
    fun sanitizesUrlsEmbeddedInText() {
        assertEquals(
            "fetch failed: https://h/x?token=[REDACTED] (timeout)",
            UrlSanitizer.sanitizeText("fetch failed: https://u:p@h/x?token=t0k (timeout)"),
        )
    }

    @Test
    fun redactForLogCoversUrlsAndSecretShapes() {
        val secrets = listOf(
            "https://u:urlpass@h/x" to "urlpass",
            "Authorization: Bearer bearersecret" to "bearersecret",
            "api_key=plainsecret" to "plainsecret",
            "\"apiKey\": \"jsonsecret\"" to "jsonsecret",
            "apiKey: camelsecret" to "camelsecret",
            "using glpat-abcdefghijklmnopqrstuv" to "glpat-abcdefghijklmnopqrstuv",
            "aws AKIAABCDEFGHIJKLMNOP" to "AKIAABCDEFGHIJKLMNOP",
        )
        for ((input, secret) in secrets) {
            assertFalse(secret in TextRedactor.redactForLog(input), "leaked '$secret' from '$input'")
        }
    }
}
