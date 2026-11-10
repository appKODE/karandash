package io.karandash.plugins.ai.core.rules

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiSizePolicyTest {
    @Test
    fun `isUnconfigured returns true when all fields are null`() {
        assertTrue(AiSkipPolicy().isUnconfigured())
    }

    @Test
    fun `isUnconfigured returns false when maxFiles is set`() {
        assertFalse(AiSkipPolicy(maxFiles = 100).isUnconfigured())
    }

    @Test
    fun `isUnconfigured returns false when maxTotalLines is set`() {
        assertFalse(AiSkipPolicy(maxTotalLines = 5000).isUnconfigured())
    }

    @Test
    fun `isUnconfigured returns false when maxCommits is set`() {
        assertFalse(AiSkipPolicy(maxCommits = 50).isUnconfigured())
    }
}
