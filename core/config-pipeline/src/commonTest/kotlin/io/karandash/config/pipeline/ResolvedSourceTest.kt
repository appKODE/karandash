package io.karandash.config.pipeline

import io.karandash.config.merge.MergeMode
import io.karandash.manifest.ConfigSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResolvedSourceTest {
    @Test
    fun `ResolvedSource holds source and null strategy`() {
        val source = ConfigSource(url = "https://example.com/config.yaml")
        val resolved = ResolvedSource(source, null)
        assertEquals(source, resolved.source)
        assertNull(resolved.mergeStrategy)
    }

    @Test
    fun `ResolvedSource holds per-entry strategy independently`() {
        val sourceA = ConfigSource(url = "https://example.com/a.yaml")
        val sourceB = ConfigSource(url = "https://example.com/b.yaml")
        val strategyA = mapOf("rules" to MergeMode.APPEND)
        val strategyB = mapOf("rules" to MergeMode.REPLACE)

        val resolvedA = ResolvedSource(sourceA, strategyA)
        val resolvedB = ResolvedSource(sourceB, strategyB)

        // Independent per-source strategies
        assertEquals(MergeMode.APPEND, resolvedA.mergeStrategy?.get("rules"))
        assertEquals(MergeMode.REPLACE, resolvedB.mergeStrategy?.get("rules"))
    }
}
