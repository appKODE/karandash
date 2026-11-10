package io.karandash.size

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class KarandashSizeSerializerTest {
    @Test
    fun deserializeMegabytes() {
        val element = JsonPrimitive("10mb")
        val result = Json.decodeFromJsonElement(KarandashSizeSerializer, element)
        assertEquals(10L * 1_048_576, result.bytes)
    }

    @Test
    fun deserializeKilobytes() {
        val element = JsonPrimitive("512kb")
        val result = Json.decodeFromJsonElement(KarandashSizeSerializer, element)
        assertEquals(512L * 1_024, result.bytes)
    }

    @Test
    fun serializeRoundTrip() {
        val original = KarandashSize.ofMb(10)
        val encoded = Json.encodeToJsonElement(KarandashSizeSerializer, original)
        val decoded = Json.decodeFromJsonElement(KarandashSizeSerializer, encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun serializeGigabytes() {
        val encoded = Json.encodeToJsonElement(KarandashSizeSerializer, KarandashSize.ofGb(1))
        assertEquals(JsonPrimitive("1gb"), encoded)
    }
}
