package io.karandash.duration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class KarandashDurationSerializerTest {
    @Test
    fun deserializeDays() {
        val element = JsonPrimitive("7d")
        val result = Json.decodeFromJsonElement(KarandashDurationSerializer, element)
        assertEquals(604_800_000L, result.millis)
    }

    @Test
    fun deserializeCompound() {
        val element = JsonPrimitive("1h 30m")
        val result = Json.decodeFromJsonElement(KarandashDurationSerializer, element)
        assertEquals(5_400_000L, result.millis)
    }

    @Test
    fun deserializeNever() {
        val element = JsonPrimitive("never")
        val result = Json.decodeFromJsonElement(KarandashDurationSerializer, element)
        assertEquals(KarandashDuration.NEVER, result)
    }

    @Test
    fun serializeRoundTrip() {
        val original = KarandashDuration.ofMillis(95_400_000L)
        val encoded = Json.encodeToJsonElement(KarandashDurationSerializer, original)
        val decoded = Json.decodeFromJsonElement(KarandashDurationSerializer, encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun serializeNever() {
        val encoded = Json.encodeToJsonElement(KarandashDurationSerializer, KarandashDuration.NEVER)
        assertEquals(JsonPrimitive("never"), encoded)
    }
}
