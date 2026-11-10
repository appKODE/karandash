package io.karandash.plugins.ai.core.rules

import io.karandash.models.support.KarandashJson
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

class StringOrListSerializerTest {
    @Serializable
    private data class Holder(
        @Serializable(with = StringOrListSerializer::class)
        val values: List<String> = emptyList(),
    )

    private val json = KarandashJson.Default

    @Test
    fun `decodes JSON array into list`() {
        val decoded = json.decodeFromString<Holder>("""{"values": ["a", "b", "c"]}""")
        assertEquals(listOf("a", "b", "c"), decoded.values)
    }

    @Test
    fun `decodes single string into single-item list`() {
        val decoded = json.decodeFromString<Holder>("""{"values": "only item"}""")
        assertEquals(listOf("only item"), decoded.values)
    }

    @Test
    fun `decodes empty string into empty list`() {
        val decoded = json.decodeFromString<Holder>("""{"values": ""}""")
        assertEquals(emptyList(), decoded.values)
    }

    @Test
    fun `decodes whitespace-only string into empty list`() {
        val decoded = json.decodeFromString<Holder>("""{"values": "   "}""")
        assertEquals(emptyList(), decoded.values)
    }

    @Test
    fun `trims surrounding whitespace on string coercion`() {
        val decoded = json.decodeFromString<Holder>("""{"values": "  padded  "}""")
        assertEquals(listOf("padded"), decoded.values)
    }

    @Test
    fun `decodes null into empty list`() {
        val decoded = json.decodeFromString<Holder>("""{"values": null}""")
        assertEquals(emptyList(), decoded.values)
    }

    @Test
    fun `decodes missing field into default empty list`() {
        val decoded = json.decodeFromString<Holder>("""{}""")
        assertEquals(emptyList(), decoded.values)
    }

    @Test
    fun `decodes object into empty list without throwing`() {
        val decoded = json.decodeFromString<Holder>("""{"values": {"unexpected": "shape"}}""")
        assertEquals(emptyList(), decoded.values)
    }

    @Test
    fun `decodes mixed array keeping primitive content of non-string entries`() {
        val decoded = json.decodeFromString<Holder>("""{"values": ["a", 42, true]}""")
        assertEquals(listOf("a", "42", "true"), decoded.values)
    }

    @Test
    fun `decodes array with null entries skipping them`() {
        val decoded = json.decodeFromString<Holder>("""{"values": ["a", null, "b"]}""")
        assertEquals(listOf("a", "b"), decoded.values)
    }

    @Test
    fun `decodes array with nested objects skipping them`() {
        val decoded = json.decodeFromString<Holder>("""{"values": ["a", {"nested": 1}, "b"]}""")
        assertEquals(listOf("a", "b"), decoded.values)
    }

    @Test
    fun `encodes list as JSON array`() {
        val encoded = json.encodeToString(Holder.serializer(), Holder(listOf("x", "y")))
        assertEquals("""{"values":["x","y"]}""", encoded)
    }

    @Test
    fun `encodes empty list as empty JSON array`() {
        val encoded = json.encodeToString(Holder.serializer(), Holder(emptyList()))
        assertEquals("""{"values":[]}""", encoded)
    }
}
