package io.karandash.inspector

import io.karandash.loader.PluginDiscovery
import io.karandash.platform.exists
import io.karandash.platform.readText
import io.karandash.platform.writeText
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstalledPluginListerImplTest {
    /** Reads `id:version` from the file text; anything else fails verification. */
    private class FakeInspector : PluginInspector {
        var shutDown = false

        override suspend fun readMetadata(path: String): PluginFileMetadata? {
            val text = Path(path).readText()
            val parts = text.split(':')
            return if (parts.size == 2) PluginFileMetadata(parts[0], parts[0].uppercase(), parts[1]) else null
        }

        override suspend fun verifyFile(
            path: String,
            extension: String,
        ): String? = if (readMetadata(path) == null) "invalid $extension" else null

        override fun nativeLibraryExtensions(): Set<String> = setOf("so")

        override suspend fun shutdown() {
            shutDown = true
        }
    }

    private val inspector = FakeInspector()
    private val lister = InstalledPluginListerImpl(inspector, downloadCache = { null })

    private fun tempDir(): Path = TestFiles.createTestTempDirectory("lister").also { SystemFileSystem.createDirectories(it) }

    private fun file(
        dir: Path,
        name: String,
        text: String,
    ): Path = Path(dir, name).also { it.writeText(text) }

    @Test
    fun `supported formats add script and jar formats to native ones`() {
        assertEquals(setOf("so", "js", "mjs", "wasm", "jar"), lister.supportedFormats())
    }

    @Test
    fun `isDirectory distinguishes directories files and missing paths`() {
        val dir = tempDir()
        assertTrue(lister.isDirectory(dir.toString()))
        assertFalse(lister.isDirectory(file(dir, "a.jar", "a:1").toString()))
        assertFalse(lister.isDirectory(Path(dir, "missing").toString()))
    }

    @Test
    fun `lists only readable plugins with supported extensions`() =
        runTest {
            val dir = tempDir()
            file(dir, "a.jar", "a:1.0")
            file(dir, "b.wasm", "b:2.0")
            file(dir, "notes.txt", "c:3.0")
            file(dir, "broken.jar", "garbage")
            SystemFileSystem.createDirectories(Path(dir, "sub.jar"))

            val listed = lister.listInstalledPlugins(dir.toString()).sortedBy { it.id }
            assertEquals(listOf("a" to "1.0", "b" to "2.0"), listed.map { it.id to it.version })
            assertEquals("A", listed.first().name)
            assertEquals(emptyList(), lister.listInstalledPlugins(Path(dir, "missing").toString()))
        }

    @Test
    fun `verify reports missing files unsupported types and inspector errors`() =
        runTest {
            val dir = tempDir()
            assertEquals("File not found: $dir/none.jar", lister.verifyPlugin("$dir/none.jar"))
            assertTrue(lister.verifyPlugin(file(dir, "x.txt", "x:1").toString()).orEmpty().startsWith("Unsupported file type: .txt"))
            assertEquals("invalid jar", lister.verifyPlugin(file(dir, "x.jar", "nope").toString()))
            assertNull(lister.verifyPlugin(file(dir, "y.jar", "y:1").toString()))
        }

    @Test
    fun `install copies under the plugin id and refuses duplicates`() =
        runTest {
            val src = file(tempDir(), "download.jar", "my-plugin:1.2")
            val target = Path(tempDir(), "plugins")

            val info = lister.installPlugin(src.toString(), target.toString()).getOrThrow()
            assertEquals("my-plugin", info.id)
            assertEquals(Path(target, "my-plugin.jar").toString(), info.sourcePath)
            assertTrue(Path(target, "my-plugin.jar").exists())

            val again = lister.installPlugin(src.toString(), target.toString())
            assertTrue(
                again
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("already exists")
            )
        }

    @Test
    fun `install fails when a verified file then yields no metadata`() =
        runTest {
            val unreadable = object : PluginInspector {
                override suspend fun readMetadata(path: String): PluginFileMetadata? = null

                override suspend fun verifyFile(
                    path: String,
                    extension: String,
                ): String? = null

                override fun nativeLibraryExtensions(): Set<String> = emptySet()

                override suspend fun shutdown() = Unit
            }
            val src = file(tempDir(), "odd.jar", "odd")

            val result = InstalledPluginListerImpl(unreadable, downloadCache = { null })
                .installPlugin(src.toString(), Path(tempDir(), "plugins").toString())

            assertEquals("Failed to read plugin metadata: $src", result.exceptionOrNull()?.message)
        }

    @Test
    fun `default plugin dir comes from plugin discovery and probe defaults to metadata`() =
        runTest {
            assertEquals(PluginDiscovery.defaultPluginDirPath(), lister.defaultPluginDirPath())
            val src = file(tempDir(), "p.jar", "p:3")
            assertEquals(inspector.readMetadata(src.toString()), inspector.probeMetadata(src.toString()))
        }

    @Test
    fun `install of an invalid file fails without copying`() =
        runTest {
            val target = Path(tempDir(), "plugins")
            val result = lister.installPlugin(file(tempDir(), "bad.jar", "bad").toString(), target.toString())
            assertEquals("invalid jar", result.exceptionOrNull()?.message)
            assertFalse(target.exists())
        }

    @Test
    fun `remove deletes the file carrying the plugin id`() =
        runTest {
            val dir = tempDir()
            file(dir, "a.jar", "a:1")
            file(dir, "readme.md", "b:1")
            file(dir, "broken.jar", "garbage")
            val b = file(dir, "b.jar", "b:1")

            assertFalse(lister.removePlugin("b", Path(dir, "missing").toString()))
            assertFalse(lister.removePlugin("zzz", dir.toString()))
            assertTrue(lister.removePlugin("b", dir.toString()))
            assertFalse(b.exists())
        }

    @Test
    fun `download over plain http fails and shutdown reaches the inspector`() =
        runTest {
            val result = lister.downloadAndUpdate("http://x/p.jar", tempDir().toString(), null, "p")
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("Only HTTPS")
            )
            lister.shutdown()
            assertTrue(inspector.shutDown)
        }

    @Test
    fun `loopback download uses its own client which shutdown closes too`() =
        runTest {
            val result = lister.downloadAndUpdate("http://localhost/p.jar", tempDir().toString(), null, "p", allowLocalhost = true)
            assertTrue(result.isFailure)
            lister.shutdown()
            assertTrue(inspector.shutDown)
        }
}
