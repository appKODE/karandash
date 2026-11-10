package io.karandash.config.pipeline

import io.karandash.config.RuleMode
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleMetaConcernAreaKeyTest {
    @Test
    fun `rules rule concernArea is read into the rule meta`() {
        val raw = mapOf("rules" to mapOf("ai-fix" to mapOf("mode" to "fallback", "concern-area" to "naming")))
        val meta = ConfigMapParser
            .parseFullConfig(raw)
            .config.rules
            .metaFor("aiFix")
        assertEquals("naming", meta.concernArea)
        assertEquals(RuleMode.FALLBACK, meta.mode)
    }
}
