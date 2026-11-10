package io.karandash.config.pipeline

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigMapParserHeadersTest {
    @Test
    fun `header names under a headers map keep their spelling`() {
        val raw = mapOf(
            "reporters" to mapOf(
                "webhook" to mapOf(
                    "auth-token" to "t",
                    "headers" to mapOf("X-Source" to "karandash", "x-trace_id" to "1"),
                ),
            ),
        )
        val webhook = checkNotNull(
            ConfigMapParser
                .parseFullConfig(raw)
                .config.reporters
                ?.get("webhook")
        ).jsonObject

        val headers = checkNotNull(webhook["headers"]).jsonObject
        assertEquals(setOf("X-Source", "x-trace_id"), headers.keys)
        assertEquals("karandash", checkNotNull(headers["X-Source"]).jsonPrimitive.content)
        // sibling schema keys are still normalized
        assertEquals("t", checkNotNull(webhook["authToken"]).jsonPrimitive.content)
    }
}
