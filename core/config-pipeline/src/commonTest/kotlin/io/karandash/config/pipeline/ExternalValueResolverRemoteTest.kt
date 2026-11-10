package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.content.LocalContentReader
import io.karandash.http.HttpAuth
import io.karandash.manifest.RemoteFileResolution
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/** `{ file: }`, `{ file-local: }` and `{ file-remote: }` resolution of a config fetched from a remote URL. */
class ExternalValueResolverRemoteTest {
    private val dir = TestFiles.createTestTempDirectory("evr-remote").also {
        SystemFileSystem.createDirectories(it)
        write(it, "local.md", "local\n")
        write(it, "both.md", "local both\n")
        write(it, "huge.md", "x".repeat(LocalContentReader.MAX_FILE_SIZE_BYTES + 1))
    }

    private val remote = mapOf(
        "https://h/cfg/remote.md" to "remote\n",
        "https://h/cfg/both.md" to "remote both\n",
    )
    private val fetched = mutableListOf<String>()
    private val warnings = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(dir)
    }

    private fun write(
        dir: Path,
        name: String,
        content: String,
    ) = SystemFileSystem.sink(Path(dir, name)).buffered().use { it.writeString(content) }

    private fun context(
        resolution: RemoteFileResolution,
        local: Path? = dir,
        fetch: (String) -> String = { url -> remote[url] ?: error("404 $url") },
    ) = FileResolutionContext.Remote(
        baseUrl = "https://h/cfg",
        auth = HttpAuth.Bearer("t"),
        fetcher = { url, _ -> fetch(url.also { fetched += it }) },
        localBasePath = local,
        resolution = resolution,
        onWarning = { warnings += it },
    )

    private fun ref(
        key: String,
        path: String,
    ) = JsonObject(mapOf("v" to JsonObject(mapOf(key to JsonPrimitive(path)))))

    private suspend fun resolve(
        key: String,
        path: String,
        context: FileResolutionContext,
    ): String = ((ExternalValueResolver.resolve(ref(key, path), context) as JsonObject)["v"] as JsonPrimitive).content

    @Test
    fun `each resolution mode picks the documented source for a plain file reference`() =
        runTest {
            // mode to (local only, remote only, both) outcome; null means the reference fails
            val expected = mapOf(
                RemoteFileResolution.LOCAL to listOf("local", null, "local both"),
                RemoteFileResolution.FALLBACK_REMOTE to listOf("local", "remote", "local both"),
                RemoteFileResolution.FALLBACK_LOCAL to listOf("local", "remote", "remote both"),
                RemoteFileResolution.STRICT to listOf(null, "remote", "remote both"),
            )
            expected.forEach { (mode, outcomes) ->
                listOf("local.md", "remote.md", "both.md").zip(outcomes).forEach { (path, outcome) ->
                    val actual = try {
                        resolve("file", path, context(mode))
                    } catch (e: ConfigException) {
                        assertTrue(e.message.orEmpty().contains(path), e.message)
                        null
                    }
                    assertEquals(outcome, actual, "$mode $path")
                }
            }
            assertEquals(1, warnings.size, warnings.toString())
        }

    @Test
    fun `explicit local and remote keys ignore the resolution mode`() =
        runTest {
            RemoteFileResolution.entries.forEach { mode ->
                assertEquals("local both", resolve("file-local", "both.md", context(mode)))
                assertEquals("remote both", resolve("file-remote", "both.md", context(mode)))
                assertFailsWith<ConfigException> { resolve("file-remote", "local.md", context(mode)) }
                assertFailsWith<ConfigException> { resolve("file-local", "remote.md", context(mode)) }
            }
        }

    @Test
    fun `references without a usable local directory or path fail`() =
        runTest {
            val failures = listOf(
                Triple("file-local", "local.md", context(RemoteFileResolution.LOCAL, local = null)),
                Triple("file", "local.md", context(RemoteFileResolution.LOCAL, local = null)),
                Triple("file", "local.md", context(RemoteFileResolution.FALLBACK_LOCAL, local = null)),
                Triple("file-local", " ", context(RemoteFileResolution.LOCAL)),
                Triple("file", "", context(RemoteFileResolution.FALLBACK_REMOTE)),
                Triple("file", "", context(RemoteFileResolution.STRICT)),
                Triple("file-remote", "../../escape.md", context(RemoteFileResolution.STRICT)),
                Triple("file-remote", "https://evil/x.md", context(RemoteFileResolution.STRICT)),
            )
            failures.forEach { (key, path, ctx) ->
                assertFailsWith<ConfigException>("$key $path") { resolve(key, path, ctx) }
            }
            assertEquals("remote", resolve("file", "remote.md", context(RemoteFileResolution.FALLBACK_REMOTE, local = null)))
        }

    @Test
    fun `a config exception from the fetcher is rethrown unchanged`() =
        runTest {
            val original = ConfigException("already explained")
            val ctx = context(RemoteFileResolution.STRICT) { throw original }

            val thrown = assertFailsWith<ConfigException> { resolve("file", "remote.md", ctx) }

            assertEquals(original, thrown)
        }

    @Test
    fun `an unreadable local fallback is reported and the reference fails`() =
        runTest {
            val error = assertFailsWith<ConfigException> { resolve("file", "huge.md", context(RemoteFileResolution.FALLBACK_LOCAL)) }

            assertTrue(error.message.orEmpty().contains("huge.md"), error.message)
            assertTrue(warnings.single().startsWith("Local fallback for 'huge.md' failed"), warnings.toString())
        }

    @Test
    fun `denied parents and nested arrays are walked like local resolution`() =
        runTest {
            val tree = JsonObject(
                mapOf(
                    "token" to JsonObject(mapOf("file-remote" to JsonPrimitive("remote.md"))),
                    "list" to JsonArray(listOf(JsonObject(mapOf("file-remote" to JsonPrimitive("remote.md"))), JsonPrimitive(1))),
                    "two" to JsonObject(mapOf("file" to JsonPrimitive("a"), "other" to JsonPrimitive("b"))),
                ),
            )

            val resolved = ExternalValueResolver.resolve(tree, context(RemoteFileResolution.STRICT)) as JsonObject

            assertEquals(tree["token"], resolved["token"])
            assertEquals(JsonArray(listOf(JsonPrimitive("remote"), JsonPrimitive(1))), resolved["list"])
            assertEquals(tree["two"], resolved["two"])
            assertEquals(listOf("https://h/cfg/remote.md"), fetched)
            assertTrue(ExternalValueResolver.hasFileReference(tree))
            assertFalse(ExternalValueResolver.hasFileReference(JsonObject(mapOf("token" to tree["token"] as JsonElement))))
        }

    @Test
    fun `a local context reports missing files when asked and fails otherwise`() =
        runTest {
            val missing = mutableListOf<String>()
            val lenient = FileResolutionContext.Local(dir) { reference, _ -> missing += reference }

            assertEquals("", resolve("file", "absent.md", lenient))
            assertEquals("local", resolve("file", "local.md", lenient))
            assertEquals(listOf("absent.md"), missing)
            listOf("absent.md", " ").forEach { path ->
                assertFailsWith<ConfigException>(path) { resolve("file", path, FileResolutionContext.Local(dir)) }
            }
        }

    @Test
    fun `a missing local file target hints at the reference to fix`() =
        runTest {
            val error = assertFailsWith<ConfigException> { resolve("file", "absent.md", FileResolutionContext.Local(dir)) }
            assertTrue(error.message.orEmpty().endsWith("External value file not found: absent.md"), error.message)
            assertEquals(
                "Create 'absent.md' or fix the '{ file: ... }' path; it resolves from the config file's directory.",
                error.hint,
            )
        }
}
