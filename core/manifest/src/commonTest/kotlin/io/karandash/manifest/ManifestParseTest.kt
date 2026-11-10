package io.karandash.manifest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestParseTest {
    @Test
    fun `non object manifest yields defaults`() {
        assertEquals(ManifestData(), ManifestReader.parseManifest(JsonArray(emptyList())))
        assertEquals(ManifestData(), ManifestReader.parseManifest(JsonPrimitive("x")))
    }

    @Test
    fun `missing or scalar plugins block yields defaults`() {
        assertEquals(ManifestData(), ManifestReader.parseManifest(JsonObject(emptyMap())))
        assertEquals(ManifestData(), ManifestReader.parseManifest(buildJsonObject { put("plugins", "oops") }))
        assertEquals(ManifestData(), ManifestReader.parseManifest(buildJsonObject { put("plugins", JsonNull) }))
    }

    @Test
    fun `list form reads declarations with warn default`() {
        val manifest = buildJsonObject {
            putJsonArray("plugins") {
                add(JsonPrimitive("com.example.a"))
                add(
                    buildJsonObject {
                        put("id", "com.example.b")
                        put("version", "1.0.0")
                        put("source", "https://example.com/b.jar")
                    },
                )
                add(JsonNull)
                add(JsonPrimitive(7))
            }
        }
        val data = ManifestReader.parseManifest(manifest)
        assertEquals(
            listOf(
                PluginDeclaration("com.example.a"),
                PluginDeclaration("com.example.b", "1.0.0", "https://example.com/b.jar"),
            ),
            data.pluginDeclarations,
        )
        assertEquals(MissingPluginBehavior.WARN, data.onMissing)
        assertNull(data.registryUrl)
    }

    @Test
    fun `map form reads every setting with kebab keys`() {
        val manifest = buildJsonObject {
            putJsonObject("plugins") {
                put("registry-url", "https://registry.example.com/index.json")
                put("directory", "/opt/plugins")
                put("on-missing", "fail")
                put("token", "t0k")
                put("auth-header", "PRIVATE-TOKEN")
                put("allow-localhost", true)
                putJsonArray("install") { add(JsonPrimitive("com.example.a")) }
            }
        }
        assertEquals(
            ManifestData(
                pluginDeclarations = listOf(PluginDeclaration("com.example.a")),
                registryUrl = "https://registry.example.com/index.json",
                pluginDir = "/opt/plugins",
                onMissing = MissingPluginBehavior.FAIL,
                registryToken = "t0k",
                registryAuthHeader = "PRIVATE-TOKEN",
                allowLocalhost = true,
            ),
            ManifestReader.parseManifest(manifest),
        )
    }

    @Test
    fun `map form matches camelCase and snake_case key variants`() {
        val manifest = buildJsonObject {
            putJsonObject("plugins") {
                put("registryUrl", "https://r.example.com")
                put("on_missing", "IGNORE")
                put("authHeader", "X-Token")
                put("allowLocalhost", "false")
            }
        }
        val data = ManifestReader.parseManifest(manifest)
        assertEquals("https://r.example.com", data.registryUrl)
        assertEquals(MissingPluginBehavior.IGNORE, data.onMissing)
        assertEquals("X-Token", data.registryAuthHeader)
        assertEquals(false, data.allowLocalhost)
        assertTrue(data.pluginDeclarations.isEmpty())
    }

    @Test
    fun `unknown on-missing value falls back to warn`() {
        val manifest = buildJsonObject { putJsonObject("plugins") { put("on-missing", "explode") } }
        assertEquals(MissingPluginBehavior.WARN, ManifestReader.parseManifest(manifest).onMissing)
    }

    @Test
    fun `nested arrays inside the plugins map are converted`() {
        val manifest = buildJsonObject {
            putJsonObject("plugins") {
                put("install", buildJsonArray { add(buildJsonObject { put("id", "x") }) })
            }
        }
        assertEquals(listOf(PluginDeclaration("x")), ManifestReader.parseManifest(manifest).pluginDeclarations)
    }

    @Test
    fun `remote file resolution parses every alias`() {
        assertEquals(RemoteFileResolution.LOCAL, RemoteFileResolution.fromString("LOCAL"))
        assertEquals(RemoteFileResolution.FALLBACK_REMOTE, RemoteFileResolution.fromString("fallback_remote"))
        assertEquals(RemoteFileResolution.FALLBACK_REMOTE, RemoteFileResolution.fromString("fallbackRemote"))
        assertEquals(RemoteFileResolution.FALLBACK_LOCAL, RemoteFileResolution.fromString("fallback"))
        assertEquals(RemoteFileResolution.FALLBACK_LOCAL, RemoteFileResolution.fromString("fallback-local"))
        assertEquals(RemoteFileResolution.FALLBACK_LOCAL, RemoteFileResolution.fromString("FallbackLocal"))
        assertEquals(RemoteFileResolution.STRICT, RemoteFileResolution.fromString("strict"))
        assertNull(RemoteFileResolution.fromString("remote-only"))
    }

    @Test
    fun `extends entries keep their defaults`() {
        val url = ExtendsEntry.UrlEntry(ConfigSource("https://example.com/base.yaml"))
        assertEquals(RemoteFileResolution.LOCAL, url.fileResolution)
        assertNull(url.condition)
        assertNull(url.mergeStrategy)
        assertEquals(emptyList(), url.source.emptyAuthVars)
        assertEquals("*.yaml", ExtendsEntry.GlobEntry("*.yaml").pattern)
        assertEquals("standard", ExtendsEntry.ManifestRef("standard", condition = "ci").name)
        assertEquals(CachePolicy.DEFAULT_CACHE_TTL, CachePolicy().ttl)
    }
}
