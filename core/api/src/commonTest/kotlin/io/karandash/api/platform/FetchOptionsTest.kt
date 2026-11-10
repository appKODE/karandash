package io.karandash.api.platform

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FetchOptionsTest {
    @Test
    fun `default context lines matches documented constant`() {
        val options = FetchOptions()
        assertEquals(FetchOptions.DEFAULT_CONTEXT_LINES, options.contextLines)
        assertEquals(3, options.contextLines)
    }

    @Test
    fun `zero context lines accepted`() {
        val options = FetchOptions(contextLines = 0)
        assertEquals(0, options.contextLines)
    }

    @Test
    fun `maximum context lines accepted`() {
        val options = FetchOptions(contextLines = FetchOptions.MAX_CONTEXT_LINES)
        assertEquals(50, options.contextLines)
    }

    @Test
    fun `negative context lines rejected`() {
        val thrown = assertFailsWith<IllegalArgumentException> { FetchOptions(contextLines = -1) }
        assertEquals(true, thrown.message?.contains("contextLines"))
    }

    @Test
    fun `over maximum context lines rejected`() {
        assertFailsWith<IllegalArgumentException> { FetchOptions(contextLines = 51) }
    }

    @Test
    fun `serialization round trips with kebab case key`() {
        val original = FetchOptions(contextLines = 10)
        val json = Json.encodeToString(FetchOptions.serializer(), original)
        assertEquals("""{"context-lines":10}""", json)
        val decoded = Json.decodeFromString(FetchOptions.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun `deserialization accepts missing field and applies default`() {
        val decoded = Json.decodeFromString(FetchOptions.serializer(), "{}")
        assertEquals(FetchOptions.DEFAULT_CONTEXT_LINES, decoded.contextLines)
    }
}
