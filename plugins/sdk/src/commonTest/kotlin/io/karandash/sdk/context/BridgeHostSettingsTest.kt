package io.karandash.sdk.context

import io.karandash.utils.mr.ShortSha
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeHostSettingsTest {
    private fun context(
        source: String?,
        vararg options: String,
    ): JsonObject =
        buildJsonObject {
            put(
                BridgeHostSettings.PATTERNS,
                buildJsonObject {
                    if (source != null) {
                        put(
                            "p",
                            buildJsonObject {
                                put(BridgeHostSettings.PATTERN_SOURCE, source)
                                put(
                                    BridgeHostSettings.PATTERN_OPTIONS,
                                    buildJsonArray { options.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                                )
                            },
                        )
                    }
                },
            )
        }

    @Test
    fun `a host pattern is recompiled with its options as flags`() {
        val regex = assertNotNull(BridgeHostSettings.pattern(context("^ab\$", "IGNORE_CASE"), "p"))
        assertTrue(regex.matches("AB"))
        val multi = assertNotNull(BridgeHostSettings.pattern(context("^b\$", "MULTILINE", "DOT_MATCHES_ALL", "COMMENTS"), "p"))
        assertTrue(multi.containsMatchIn("a\nb\nc"))
        assertNotNull(BridgeHostSettings.pattern(context("a.c"), "p")).let { assertTrue(it.matches("abc")) }
    }

    @Test
    fun `a literal pattern is escaped`() {
        val regex = assertNotNull(BridgeHostSettings.pattern(context("a.c", "LITERAL"), "p"))
        assertTrue(regex.matches("a.c"))
        assertTrue(!regex.matches("abc"))
    }

    @Test
    fun `a missing or broken pattern gives null`() {
        assertNull(BridgeHostSettings.pattern(context(null), "p"))
        assertNull(BridgeHostSettings.pattern(context("a", "IGNORE_CASE"), "other"))
        assertNull(BridgeHostSettings.pattern(JsonObject(emptyMap()), "p"))
        assertNull(BridgeHostSettings.pattern(context("(unclosed"), "p"))
    }

    @Test
    fun `apply sets the short sha length only for a positive number`() {
        val before = ShortSha.length
        try {
            BridgeHostSettings.apply(buildJsonObject { put(BridgeHostSettings.SHORT_SHA_LENGTH, 0) })
            assertEquals(before, ShortSha.length)
            BridgeHostSettings.apply(buildJsonObject { put(BridgeHostSettings.SHORT_SHA_LENGTH, 11) })
            assertEquals(11, ShortSha.length)
        } finally {
            ShortSha.configure(before)
        }
    }
}
