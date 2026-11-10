package io.karandash.manifest

import io.karandash.config.merge.MergeMode
import io.karandash.duration.KarandashDuration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** Manifest models omit default-valued fields when written and read every non-default field back. */
class ManifestSerializationTest {
    private fun <T> roundTrip(
        serializer: KSerializer<T>,
        value: T,
    ): String {
        val json = Json.encodeToString(serializer, value)
        assertEquals(value, Json.decodeFromString(serializer, json), json)
        return json
    }

    @Test
    fun `manifest with only defaults encodes to an empty object`() {
        assertEquals("{}", roundTrip(ManifestData.serializer(), ManifestData()))
    }

    @Test
    fun `manifest with every field set survives a round trip`() {
        val full =
            ManifestData(
                pluginDeclarations = listOf(PluginDeclaration("acme", "1.0", "https://example.test/acme.jar")),
                registryUrl = "https://registry.test",
                pluginDir = "plugins",
                onMissing = MissingPluginBehavior.FAIL,
                registryToken = "t",
                registryAuthHeader = "X-Token",
                allowLocalhost = true,
            )
        val json = roundTrip(ManifestData.serializer(), full)
        listOf("pluginDeclarations", "registryUrl", "pluginDir", "onMissing", "registryToken", "registryAuthHeader", "allowLocalhost")
            .forEach { assertEquals(true, "\"$it\"" in json, json) }
    }

    @Test
    fun `plugin declaration writes only the id when version and source are unset`() {
        assertEquals("""{"id":"acme"}""", roundTrip(PluginDeclaration.serializer(), PluginDeclaration("acme")))
    }

    @Test
    fun `glob and manifest-ref entries omit unset condition cache and merge strategy`() {
        assertEquals("""{"pattern":"*.yaml"}""", roundTrip(ExtendsEntry.GlobEntry.serializer(), ExtendsEntry.GlobEntry("*.yaml")))
        assertEquals("""{"name":"@std"}""", roundTrip(ExtendsEntry.ManifestRef.serializer(), ExtendsEntry.ManifestRef("@std")))
    }

    @Test
    fun `glob and manifest-ref entries keep every set field`() {
        val strategy = mapOf("rules" to MergeMode.APPEND)
        roundTrip(
            ExtendsEntry.GlobEntry.serializer(),
            ExtendsEntry.GlobEntry("*.yaml", "ci", CachePolicy(KarandashDuration.ofSeconds(5)), strategy),
        )
        roundTrip(ExtendsEntry.ManifestRef.serializer(), ExtendsEntry.ManifestRef("@std", "ci", strategy))
    }

    @Test
    fun `cache policy omits the default ttl and keeps a custom one`() {
        assertEquals("{}", roundTrip(CachePolicy.serializer(), CachePolicy()))
        roundTrip(CachePolicy.serializer(), CachePolicy(KarandashDuration.ofSeconds(0)))
    }
}
