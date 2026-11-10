package io.karandash.api.ai

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AiContextBlockTest {
    @Test
    fun `charCount accounts for heading content and newlines`() {
        val block = AiContextBlock(
            heading = "#### Related files",
            content = "Auth.kt uses TokenValidator",
            priority = 200,
            source = "related-files",
        )
        // heading (18) + newline (1) + content (27) + newline (1) = 47
        assertEquals(18 + 1 + 27 + 1, block.charCount)
    }

    @Test
    fun `empty heading and content produces two newlines`() {
        val block = AiContextBlock(heading = "", content = "", priority = 0, source = "x")
        assertEquals(2, block.charCount)
    }

    @Test
    fun `serialization round trip preserves all fields`() {
        val original = AiContextBlock(
            heading = "#### Conventions",
            content = "indent = 4 spaces",
            priority = 100,
            source = "convention-context",
        )
        val json = Json.encodeToString(AiContextBlock.serializer(), original)
        val decoded = Json.decodeFromString(AiContextBlock.serializer(), json)
        assertEquals(original, decoded)
    }
}
