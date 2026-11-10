package io.karandash.config

import io.karandash.duration.KarandashDuration
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeoutConfigTest {
    private val json = KarandashJson.Default

    @Test
    fun `defaults are sensible`() {
        val config = TimeoutConfig()
        assertEquals(60_000L, config.http.millis)
        assertEquals(10_000L, config.enrichment.millis)
        assertEquals(10_000L, config.pluginShutdown.millis)
        assertEquals(60_000L, config.cacheTtl.millis)
        assertEquals(null, config.ruleExecution, "unset = no per-rule timeout")
    }

    @Test
    fun `deserialize from JSON`() {
        val element = JsonObject(
            mapOf(
                "http" to JsonPrimitive("45s"),
                "enrichment" to JsonPrimitive("15s"),
                "pluginShutdown" to JsonPrimitive("10s"),
                "cacheTtl" to JsonPrimitive("5m"),
                "ruleExecution" to JsonPrimitive("2m"),
            ),
        )
        val config = json.decodeFromJsonElement(TimeoutConfig.serializer(), element)
        assertEquals(45_000L, config.http.millis)
        assertEquals(15_000L, config.enrichment.millis)
        assertEquals(10_000L, config.pluginShutdown.millis)
        assertEquals(300_000L, config.cacheTtl.millis)
        assertEquals(120_000L, config.ruleExecution?.millis)
    }

    @Test
    fun `deserialize partial config uses defaults for missing fields`() {
        val element = JsonObject(
            mapOf("http" to JsonPrimitive("45s")),
        )
        val config = json.decodeFromJsonElement(TimeoutConfig.serializer(), element)
        assertEquals(45_000L, config.http.millis)
        assertEquals(TimeoutConfig.DEFAULT_ENRICHMENT.millis, config.enrichment.millis)
        assertEquals(TimeoutConfig.DEFAULT_PLUGIN_SHUTDOWN.millis, config.pluginShutdown.millis)
    }

    @Test
    fun `round trip serialization`() {
        val original = TimeoutConfig(
            http = KarandashDuration.ofSeconds(45),
            enrichment = KarandashDuration.ofSeconds(15),
        )
        val encoded = json.encodeToString(TimeoutConfig.serializer(), original)
        val decoded = json.decodeFromString(TimeoutConfig.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `toKotlinDuration bridge works`() {
        val duration = KarandashDuration.ofSeconds(30)
        val kotlinDuration = duration.toKotlinDuration()
        assertEquals(30_000L, kotlinDuration.inWholeMilliseconds)
    }
}
