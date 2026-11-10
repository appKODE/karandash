package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.module.ConfigFormatModule
import io.karandash.http.HttpAuth
import io.karandash.manifest.ConfigSource
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Named extends sets read from `.karandash/extends-manifest.yaml` (JSON content, parsed by the injected parser). */
class ManifestResolverContractTest {
    private val dir = TestFiles.createTestTempDirectory("manifest")
    private val manifest = Path(dir, ManifestResolver.MANIFEST_PATH)
    private val json = ManifestContentParser { Json.parseToJsonElement(it) }

    @AfterTest
    fun cleanup() {
        if (SystemFileSystem.exists(manifest)) SystemFileSystem.delete(manifest)
        listOf(Path(dir, ".karandash"), dir).filter { SystemFileSystem.exists(it) }.forEach { SystemFileSystem.delete(it) }
    }

    private fun write(content: String) {
        SystemFileSystem.createDirectories(Path(dir, ".karandash"))
        SystemFileSystem.sink(manifest).buffered().use { it.writeString(content) }
    }

    private suspend fun failure(
        content: String?,
        set: String = "std",
        parser: ManifestContentParser = json,
    ): String {
        content?.let(::write)
        return assertFailsWith<ConfigException> { ManifestResolver(dir, parser).resolve(set, emptyMap()) }.message.orEmpty()
    }

    @Test
    fun `sets resolve string and object entries with env defaults and skip other values`() =
        runTest {
            write(
                """
                {"extends-sets": {
                  "std": ["https://h/${'$'}{ORG}/base.yaml", 3, [], {"url": "https://h/sec.yaml", "auth": {"type": "bearer-token", "token": "${'$'}{TOKEN}"}}],
                  "plain": "not a list"
                }}
                """.trimIndent(),
            )
            val resolver = ManifestResolver(dir, json)

            val entries = resolver.resolve("std", mapOf("ORG" to "acme", "TOKEN" to "t"))

            assertEquals(
                listOf(ConfigSource("https://h/acme/base.yaml"), ConfigSource("https://h/sec.yaml", HttpAuth.Bearer("t"))),
                entries.map { it.source },
            )
            assertEquals(mapOf("std" to listOf("https://h//base.yaml", "https://h/sec.yaml"), "plain" to emptyList()), resolver.listSets())
        }

    @Test
    fun `missing invalid or incomplete manifests fail with a reason`() =
        runTest {
            assertTrue(failure(null).contains("not found at"))
            assertTrue(failure("""{"other": {}}""").contains("missing 'extends-sets'"))
            assertTrue(failure("""[1]""").contains("missing 'extends-sets'"))
            assertTrue(failure("{ not json").contains("Invalid manifest syntax"))
            assertTrue(failure("""{"extends-sets": {"a": [], "b": []}}""", set = "c").contains("Available: a, b"))
            assertTrue(failure("""{"extends-sets": {"std": [{"auth": {}}]}}""").contains("missing 'url'"))
        }

    @Test
    fun `registry resolver reads a manifest in any registered format and refuses two`() =
        runTest {
            val registry = ConfigFormatRegistry()
            registry.register(StubFormat("json", setOf("json")))
            registry.register(StubFormat("yaml", setOf("yaml", "yml")))
            val jsonManifest = Path(dir, ".karandash/extends-manifest.json")
            SystemFileSystem.createDirectories(Path(dir, ".karandash"))
            SystemFileSystem.sink(jsonManifest).buffered().use { it.writeString("""{"extends-sets": {"std": ["a.yaml"]}}""") }
            try {
                assertEquals(listOf(ConfigSource("a.yaml")), ManifestResolver(dir, registry).resolve("std", emptyMap()).map { it.source })
                write("""{"extends-sets": {}}""")
                val error = assertFailsWith<ConfigException> { ManifestResolver(dir, registry).listSets() }
                assertTrue(error.message.orEmpty().contains("More than one extends manifest"))
            } finally {
                SystemFileSystem.delete(jsonManifest)
            }
        }

    private class StubFormat(
        override val formatId: String,
        override val fileExtensions: Set<String>,
    ) : ConfigFormatModule {
        override suspend fun parseToJsonElement(content: String): JsonElement = Json.parseToJsonElement(content)
    }
}
