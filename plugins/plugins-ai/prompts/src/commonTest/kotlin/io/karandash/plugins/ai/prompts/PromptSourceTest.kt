package io.karandash.plugins.ai.prompts

import io.karandash.api.support.mapToJsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PromptSourceTest {
    private fun fromConfig(config: Map<String, Any?>): PromptSource = PromptSource.fromJson(mapToJsonElement(config))

    @Test
    fun `fromConfig returns Builtin when no keys present`() {
        val source = fromConfig(emptyMap())
        assertIs<PromptSource.Builtin>(source)
    }

    @Test
    fun `fromConfig returns Inline when inline key present`() {
        val source = fromConfig(mapOf("inline" to "custom prompt"))
        assertIs<PromptSource.Inline>(source)
        assertEquals("custom prompt", source.text)
    }

    @Test
    fun `fromConfig returns File when file key present`() {
        val source = fromConfig(mapOf("file" to "/path/to/prompt.md"))
        assertIs<PromptSource.File>(source)
        assertEquals("/path/to/prompt.md", source.path)
    }

    @Test
    fun `fromConfig returns Url when url key present`() {
        val source = fromConfig(
            mapOf(
                "url" to "https://example.com/prompt.toml",
                "format" to "toml",
                "section" to "review.system",
                "sha256" to "abc123",
            ),
        )
        assertIs<PromptSource.Url>(source)
        assertEquals("https://example.com/prompt.toml", source.url)
        assertEquals("toml", source.format)
        assertEquals("review.system", source.section)
        assertEquals("abc123", source.sha256)
    }

    @Test
    fun `fromConfig Url defaults format to text`() {
        val source = fromConfig(mapOf("url" to "https://example.com/prompt.txt"))
        assertIs<PromptSource.Url>(source)
        assertEquals("text", source.format)
        assertNull(source.section)
        assertNull(source.sha256)
    }

    @Test
    fun `fromConfig url takes priority over file`() {
        val source = fromConfig(
            mapOf(
                "url" to "https://example.com/prompt.txt",
                "file" to "/local/prompt.txt",
            ),
        )
        assertIs<PromptSource.Url>(source)
    }

    @Test
    fun `fromConfig file takes priority over inline`() {
        val source = fromConfig(
            mapOf(
                "file" to "/local/prompt.txt",
                "inline" to "some text",
            ),
        )
        assertIs<PromptSource.File>(source)
    }

    @Test
    fun `fromConfig returns Pair when system and user keys present`() {
        val source = fromConfig(
            mapOf(
                "system" to mapOf("inline" to "You are a code reviewer."),
                "user" to mapOf("inline" to "Review this diff."),
            ),
        )
        assertIs<PromptSource.Pair>(source)
        val system = source.system
        val user = source.user
        assertIs<PromptSource.Inline>(system)
        assertIs<PromptSource.Inline>(user)
        assertEquals("You are a code reviewer.", system.text)
        assertEquals("Review this diff.", user.text)
    }

    @Test
    fun `fromConfig returns Pair with Builtin user when only system present`() {
        val source = fromConfig(
            mapOf("system" to mapOf("inline" to "System prompt")),
        )
        assertIs<PromptSource.Pair>(source)
        assertIs<PromptSource.Inline>(source.system)
        assertIs<PromptSource.Builtin>(source.user)
    }

    @Test
    fun `fromConfig Pair takes priority over url and file`() {
        val source = fromConfig(
            mapOf(
                "system" to mapOf("inline" to "system"),
                "url" to "https://example.com/prompt.txt",
                "file" to "/local/prompt.txt",
            ),
        )
        assertIs<PromptSource.Pair>(source)
    }

    @Test
    fun `fromConfig backward compatible when no system or user keys`() {
        val source = fromConfig(mapOf("inline" to "legacy prompt"))
        assertIs<PromptSource.Inline>(source)
        assertEquals("legacy prompt", source.text)
    }

    @Test
    fun `fromJson returns Layered when layered wrapper key present with include-builtin`() {
        val element = buildJsonObject {
            put(
                "layered",
                buildJsonObject {
                    put("include-builtin", JsonPrimitive(true))
                    put(
                        "sources",
                        buildJsonArray {
                            add(buildJsonObject { put("file", JsonPrimitive(".karandash/review-prompt.md")) })
                        }
                    )
                }
            )
        }
        val source = PromptSource.fromJson(element)
        assertIs<PromptSource.Layered>(source)
        assertEquals(true, source.includeBuiltin)
        assertEquals(1, source.sources.size)
        assertIs<PromptSource.File>(source.sources[0])
        assertEquals(".karandash/review-prompt.md", (source.sources[0] as PromptSource.File).path)
    }

    @Test
    fun `fromJson returns Layered when sources present at top level without layered wrapper`() {
        val element = buildJsonObject {
            put(
                "sources",
                buildJsonArray {
                    add(buildJsonObject { put("file", JsonPrimitive("/prompt.md")) })
                }
            )
            put("include-builtin", JsonPrimitive(false))
        }
        val source = PromptSource.fromJson(element)
        assertIs<PromptSource.Layered>(source)
        assertEquals(false, source.includeBuiltin)
        assertEquals(1, source.sources.size)
    }

    @Test
    fun `fromJson returns Builtin when empty object given`() {
        val source = PromptSource.fromJson(buildJsonObject {})
        assertIs<PromptSource.Builtin>(source)
    }

    @Test
    fun `fromJson defaults includeBuiltin to false and drops nested layered sources`() {
        val nested = buildJsonObject {
            put("sources", buildJsonArray { add(buildJsonObject { put("file", JsonPrimitive("/n.md")) }) })
        }
        val sources = buildJsonArray {
            add(nested)
            add(buildJsonObject { put("file", JsonPrimitive("/a.md")) })
        }
        val wrapped = PromptSource.fromJson(buildJsonObject { put("layered", buildJsonObject { put("sources", sources) }) })
        val topLevel = PromptSource.fromJson(buildJsonObject { put("sources", sources) })

        for (source in listOf(wrapped, topLevel)) {
            assertIs<PromptSource.Layered>(source)
            assertEquals(false, source.includeBuiltin)
            assertEquals(listOf<PromptSource>(PromptSource.File("/a.md")), source.sources)
        }
    }
}
