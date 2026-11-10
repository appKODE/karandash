package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.module.ConfigFormatModule
import io.karandash.manifest.ConfigSource
import io.karandash.manifest.ExtendsEntry
import io.karandash.manifest.RemoteFileResolution
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.config.anyToJsonElement
import io.karandash.testfixtures.TestFiles
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A remote layer's `{ file: }` values may not read local files outside the project root
 * unless the `extends:` entry sets `trust: true`; local `extends:` chains with `../..` urls keep working.
 */
class RemoteLayerLocalPathTest {
    private val root = TestFiles.createTestTempDirectory("sec2")
    private val project = Path(root, "projects", "base-kmp")
    private val configDir = Path(project, ".karandash")

    init {
        write(Path(root, "outside.md"), "secret")
        write(Path(project, "README.md"), "readme")
        write(Path(configDir, "local.md"), "local")
    }

    @AfterTest
    fun cleanup() = deleteTree(root)

    private fun deleteTree(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) SystemFileSystem.list(path).forEach(::deleteTree)
        SystemFileSystem.delete(path, mustExist = false)
    }

    private fun write(
        path: Path,
        content: String,
    ) {
        path.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(path).buffered().use { it.writeString(content) }
    }

    private fun context(
        resolution: RemoteFileResolution = RemoteFileResolution.LOCAL,
        trusted: Boolean = false,
    ) = FileResolutionContext.Remote(
        baseUrl = "https://h/org/repo/.karandash",
        auth = null,
        fetcher = { url, _ -> error("404 $url") },
        localBasePath = configDir,
        resolution = resolution,
        localRoot = project,
        trusted = trusted,
    )

    private suspend fun resolve(
        key: String,
        path: String,
        context: FileResolutionContext,
    ): String {
        val element = JsonObject(mapOf("v" to JsonObject(mapOf(key to JsonPrimitive(path)))))
        return ((ExternalValueResolver.resolve(element, context) as JsonObject)["v"] as JsonPrimitive).content
    }

    @Test
    fun `remote layer cannot read a local file outside the project root`() =
        runTest {
            val outside = Path(root, "outside.md").toString()
            val cases = listOf("file" to outside, "file" to "../../../outside.md", "file-local" to outside)
            RemoteFileResolution.entries.filter { it != RemoteFileResolution.STRICT }.forEach { mode ->
                cases.forEach { (key, path) ->
                    val error = assertFailsWith<ConfigException>("$mode $key $path") { resolve(key, path, context(mode)) }
                    val message = error.message.orEmpty()
                    // remote-first modes reject the path in remote validation before any local read
                    val expected = if (mode == RemoteFileResolution.LOCAL) "outside the project root" else "E0023"
                    assertTrue(expected in message, message)
                }
            }
        }

    @Test
    fun `remote layer still reads local files inside the project root`() =
        runTest {
            assertEquals("local", resolve("file", "local.md", context()))
            assertEquals("readme", resolve("file", "../README.md", context()))
            assertEquals("readme", resolve("file-local", "../README.md", context()))
        }

    @Test
    fun `trust true lifts the project root bound`() =
        runTest {
            assertEquals("secret", resolve("file", "../../../outside.md", context(trusted = true)))
        }

    @Test
    fun `chain with trust true merges a file above the project root and without trust it throws`() =
        runTest {
            val remoteUrl = "https://h/base.yaml"
            val json = object : ConfigFormatModule {
                override val formatId = "yaml"

                override suspend fun parseToJsonElement(content: String): JsonElement = KarandashJson.Default.parseToJsonElement(content)
            }
            val client = HttpClient(
                MockEngine { request ->
                    if (request.url.toString() ==
                        remoteUrl
                    ) {
                        respond("""{"v": {"file": "../../../outside.md"}}""")
                    } else {
                        respond("", HttpStatusCode.NotFound)
                    }
                },
            )
            val registry = ConfigFormatRegistry(listOf(json))
            val fetcher = RemoteConfigFetcher(client)

            suspend fun load(trustEntry: String): JsonElement {
                val rootFile = Path(configDir, "config.yaml")
                write(rootFile, """{"extends": [{"url": "$remoteUrl"$trustEntry}]}""")
                val chain = ConfigChainResolver(registry, fetcher).buildChain(ConfigSource(rootFile.toString()))
                return ConfigChainParser(registry, fetcher = fetcher).parseAndMerge(chain)
            }

            val merged = load(""", "trust": true""") as JsonObject
            assertEquals("secret", merged["v"]?.jsonPrimitive?.content)
            val error = assertFailsWith<ConfigException> { load("") }
            assertTrue("outside the project root" in error.message.orEmpty(), error.message)
        }

    @Test
    fun `extends entry parses trust`() {
        val data = JsonObject(
            mapOf(
                "extends" to anyToJsonElement(listOf(mapOf("url" to "https://h/c.yaml", "trust" to true), "https://h/d.yaml")),
            ),
        )
        val entries = ExtendsParser.parse(data, emptyMap()).filterIsInstance<ExtendsEntry.UrlEntry>()
        assertEquals(listOf(true, false), entries.map { it.source.trusted })
    }

    @Test
    fun `base-kmp style local chain with a parent-relative extends url still resolves`() =
        runTest {
            val base = Path(root, "projects", "base", ".karandash", "config.yaml")
            write(base, "base")
            write(Path(base.parent ?: root, "template.md"), "from base")
            val kmp = Path(configDir, "config.yaml")
            write(kmp, "kmp")
            val registry = ConfigFormatRegistry(listOf(stubYaml))

            val chain = ConfigChainResolver(registry).buildChain(ConfigSource(kmp.toString()))
            val merged = ConfigChainParser(registry).parseAndMerge(chain) as JsonObject

            assertEquals(2, chain.entries.size)
            assertEquals("from base", merged["template"]?.jsonPrimitive?.content)
            assertEquals("kmp", merged["name"]?.jsonPrimitive?.content)
        }

    private val stubYaml = object : ConfigFormatModule {
        override val formatId = "yaml"

        override suspend fun parseToJsonElement(content: String): JsonElement =
            when (content) {
                "kmp" -> anyToJsonElement(mapOf("extends" to "../../base/.karandash/config.yaml", "name" to "kmp"))
                else -> anyToJsonElement(mapOf("template" to mapOf("file" to "template.md"), "name" to "base"))
            }
    }
}
