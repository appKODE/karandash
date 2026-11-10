package io.karandash.cache

import io.karandash.duration.KarandashDuration
import io.karandash.models.support.KarandashJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CacheConfigTest {
    private val json = KarandashJson.Default

    @Test
    fun defaultsAreCorrect() {
        val config = CacheConfig()
        assertEquals(true, config.enabled)
        assertEquals("~/.karandash/cache", config.dir)
        assertEquals(ResponsesCacheConfig(), config.responses)
        assertEquals(DownloadsCacheConfig(), config.downloads)
        assertEquals(StateCacheConfig(), config.state)
        assertEquals(MemoryCacheConfig(), config.memory)
    }

    @Test
    fun responsesCacheConfigDefaults() {
        val c = ResponsesCacheConfig()
        assertEquals(true, c.enabled)
        assertEquals(null, c.ttl)
        assertEquals(KarandashDuration.ofSeconds(60), ResponsesCacheConfig.DEFAULT_TTL)
    }

    @Test
    fun downloadsCacheConfigDefaults() {
        val c = DownloadsCacheConfig()
        assertEquals(true, c.enabled)
        assertEquals(KarandashDuration.ofSeconds(3_600), c.ttl)
    }

    @Test
    fun stateCacheConfigDefaults() {
        val c = StateCacheConfig()
        assertEquals(true, c.enabled)
        assertEquals(KarandashDuration.ofSeconds(604_800), c.ttl)
    }

    @Test
    fun memoryCacheConfigDefaults() {
        val c = MemoryCacheConfig()
        assertEquals(512, c.maxEntries)
        assertEquals(emptyMap(), c.overrides)
    }

    @Test
    fun deserializesFromJson() {
        val input = """
            {
                "enabled": false,
                "dir": "/custom/cache",
                "responses": {
                    "enabled": false,
                    "ttl": "30s"
                },
                "downloads": {
                    "enabled": true,
                    "ttl": "2h"
                },
                "state": {
                    "enabled": true,
                    "ttl": "1h"
                },
                "memory": {
                    "maxEntries": 256,
                    "overrides": {
                        "glob-patterns": { "maxEntries": 2000 },
                        "responses": { "maxEntries": 128 }
                    }
                }
            }
        """.trimIndent()

        val config = json.decodeFromString<CacheConfig>(input)

        assertEquals(false, config.enabled)
        assertEquals("/custom/cache", config.dir)
        assertEquals(false, config.responses.enabled)
        assertEquals(KarandashDuration.ofSeconds(30), config.responses.ttl)
        assertEquals(KarandashDuration.ofHours(2), config.downloads.ttl)
        assertEquals(KarandashDuration.ofHours(1), config.state.ttl)
        assertEquals(256, config.memory.maxEntries)
        assertEquals(2000, config.memory.overrides["glob-patterns"]?.maxEntries)
        assertEquals(128, config.memory.overrides["responses"]?.maxEntries)
        assertNull(config.memory.overrides["downloads"])
    }

    @Test
    fun deserializesEmptyObject() {
        val config = json.decodeFromString<CacheConfig>("{}")
        assertEquals(CacheConfig(), config)
    }

    @Test
    fun memoryOverrideAllFieldsNullByDefault() {
        val override = MemoryOverride()
        assertNull(override.maxEntries)
    }

    @Test
    fun memoryOverrideDeserializesPartialFields() {
        val input = """{"maxEntries": 500}"""
        val override = json.decodeFromString<MemoryOverride>(input)
        assertEquals(500, override.maxEntries)
    }

    @Test
    fun roundTripSerialization() {
        val original = CacheConfig(
            enabled = true,
            dir = "/tmp/cache",
            responses = ResponsesCacheConfig(enabled = false, ttl = KarandashDuration.ofSeconds(30)),
            downloads = DownloadsCacheConfig(ttl = KarandashDuration.ofSeconds(7200)),
            state = StateCacheConfig(ttl = KarandashDuration.ofSeconds(3600)),
            memory = MemoryCacheConfig(
                maxEntries = 256,
                overrides = mapOf("glob-patterns" to MemoryOverride(maxEntries = 2000)),
            ),
        )
        val serialized = json.encodeToString(CacheConfig.serializer(), original)
        val deserialized = json.decodeFromString<CacheConfig>(serialized)
        assertEquals(original, deserialized)
    }
}
