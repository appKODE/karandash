package io.karandash.enrichment.jira

import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdfToMarkdownTest {
    private fun doc(vararg blocks: kotlinx.serialization.json.JsonObject) =
        buildJsonObject {
            put("type", "doc")
            put("content", buildJsonArray { blocks.forEach { add(it) } })
        }

    private fun paragraph(vararg texts: String) =
        buildJsonObject {
            put("type", "paragraph")
            put(
                "content",
                buildJsonArray {
                    texts.forEach { t ->
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", t)
                            }
                        )
                    }
                }
            )
        }

    @Test
    fun `returns empty string for null input`() {
        assertEquals("", AdfToMarkdown.convert(null))
    }

    @Test
    fun `returns empty string for JsonNull`() {
        assertEquals("", AdfToMarkdown.convert(JsonNull))
    }

    @Test
    fun `converts simple paragraph`() {
        val adf = doc(paragraph("Hello, world!"))
        assertEquals("Hello, world!", AdfToMarkdown.convert(adf))
    }

    @Test
    fun `converts heading with level`() {
        val heading = buildJsonObject {
            put("type", "heading")
            put("attrs", buildJsonObject { put("level", 2) })
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "Section Title")
                        }
                    )
                }
            )
        }
        val result = AdfToMarkdown.convert(doc(heading))
        assertEquals("## Section Title", result)
    }

    @Test
    fun `applies strong mark`() {
        val para = buildJsonObject {
            put("type", "paragraph")
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "bold text")
                            put(
                                "marks",
                                buildJsonArray {
                                    add(buildJsonObject { put("type", "strong") })
                                }
                            )
                        }
                    )
                }
            )
        }
        assertEquals("**bold text**", AdfToMarkdown.convert(doc(para)))
    }

    @Test
    fun `applies link mark`() {
        val para = buildJsonObject {
            put("type", "paragraph")
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "click here")
                            put(
                                "marks",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "link")
                                            put("attrs", buildJsonObject { put("href", "https://example.com") })
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }
        assertEquals("[click here](https://example.com)", AdfToMarkdown.convert(doc(para)))
    }

    @Test
    fun `converts bullet list`() {
        val list = buildJsonObject {
            put("type", "bulletList")
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "listItem")
                            put("content", buildJsonArray { add(paragraph("Item A")) })
                        }
                    )
                    add(
                        buildJsonObject {
                            put("type", "listItem")
                            put("content", buildJsonArray { add(paragraph("Item B")) })
                        }
                    )
                }
            )
        }
        val result = AdfToMarkdown.convert(doc(list))
        assertTrue(result.contains("- Item A"), "Expected '- Item A' in: $result")
        assertTrue(result.contains("- Item B"), "Expected '- Item B' in: $result")
    }

    @Test
    fun `converts code block`() {
        val block = buildJsonObject {
            put("type", "codeBlock")
            put("attrs", buildJsonObject { put("language", "kotlin") })
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "val x = 1")
                        }
                    )
                }
            )
        }
        val result = AdfToMarkdown.convert(doc(block))
        assertTrue(result.contains("```kotlin"), "Expected code fence in: $result")
        assertTrue(result.contains("val x = 1"), "Expected code content in: $result")
    }

    @Test
    fun `renders unsupported node type as placeholder`() {
        val unknown = buildJsonObject { put("type", "expansion") }
        val result = AdfToMarkdown.convert(doc(unknown))
        assertTrue(result.contains("[unsupported: expansion]"), "Expected placeholder in: $result")
    }

    @Test
    fun `handles mention inline node`() {
        val para = buildJsonObject {
            put("type", "paragraph")
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "mention")
                            put("attrs", buildJsonObject { put("id", "abc123") })
                        }
                    )
                }
            )
        }
        assertEquals("@user", AdfToMarkdown.convert(doc(para)))
    }

    @Test
    fun `returns empty string for non-object input`() {
        assertEquals("", AdfToMarkdown.convert(JsonPrimitive("text")))
    }

    @Test
    fun `malformed node fields are skipped instead of throwing`() {
        val adf =
            KarandashJson.Default.parseToJsonElement(
                """{"type":"doc","content":[
                  {"type":{"odd":1}},
                  {"type":"heading","attrs":{"level":{"n":2}},"content":[{"type":"text","text":"Title"}]},
                  {"type":"paragraph","content":[{"type":"text","text":["x"]},{"type":"text","text":"kept","marks":[{"type":["em"]}]}]},
                  {"type":"codeBlock","attrs":{"language":[1]},"content":[{"type":"text","text":"code"}]}
                ]}""",
            )

        assertEquals("# Title\nkept\n```\ncode\n```", AdfToMarkdown.convert(adf))
    }
}
