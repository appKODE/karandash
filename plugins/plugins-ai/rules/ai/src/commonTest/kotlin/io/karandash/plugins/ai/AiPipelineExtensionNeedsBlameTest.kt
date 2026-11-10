@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiPipelineExtensionNeedsBlameTest {
    private val extension = AiPipelineExtension(buildTestRegistry())

    @Test
    fun `blame is fetched only when an analyzer that reads blame is enabled`() {
        val off = extension.needsBlame(buildJsonObject { put("enabled", true) })
        val viaFeatures = extension.needsBlame(buildJsonObject { putJsonObject("features") { put("ownershipAnalysis", true) } })
        val viaTopLevel = extension.needsBlame(buildJsonObject { put("ownershipAnalysis", true) })

        assertFalse(off)
        assertTrue(viaFeatures)
        assertTrue(viaTopLevel)
    }
}
