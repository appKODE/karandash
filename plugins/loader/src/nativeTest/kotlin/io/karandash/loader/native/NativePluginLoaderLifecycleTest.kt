@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package io.karandash.loader.native

import io.karandash.api.ModuleRegistryImpl
import io.karandash.loader.RecordingLogger
import io.karandash.loader.RecordingPluginContext
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.copyTo
import io.karandash.platform.createDirectories
import io.karandash.platform.exists
import io.karandash.platform.readText
import io.karandash.platform.writeText
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import platform.posix.setenv
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lifecycle invariants of [NativePluginLoader] against real C plugins (`src/nativeTest/c/lifecycle_plugin.c`,
 * built by Gradle into `LIFECYCLE_PLUGIN_DIR`).
 */
class NativePluginLoaderLifecycleTest {
    private val dir = TestFiles.createTestTempDirectory("native-lifecycle").also { createDirectories(it) }
    private val log = Path(dir, "lifecycle.log").also { it.writeText("") }
    private val registry = ModuleRegistryImpl()
    private val logger = RecordingLogger()

    private fun pluginDir(vararg ids: String): Path {
        val built = checkNotNull(PlatformEnvironment.getEnv("LIFECYCLE_PLUGIN_DIR")) { "LIFECYCLE_PLUGIN_DIR not set by Gradle" }
        val plugins = Path(dir, "plugins").also { createDirectories(it) }
        val ext = if (Platform.osFamily == OsFamily.MACOSX) "dylib" else "so"
        ids.forEach { Path(built, "lib$it.$ext").copyTo(Path(plugins, "lib$it.$ext")) }
        setenv("LIFECYCLE_LOG", log.toString(), 1)
        return plugins
    }

    private fun events() = log.readText().lines().filter { it.isNotBlank() }

    @Test
    fun initializeOnLoadShutdownOnceOnUnloadAndUnloadAllIsIdempotent() =
        runTest {
            val plugins = pluginDir("native-ok")
            val loader = NativePluginLoader(registry, logger)

            val result = loader.loadFromDirectories(listOf(plugins), RecordingPluginContext())
            assertEquals(listOf("native-ok"), result.loaded, "$result")
            assertEquals(listOf("init:native-ok"), events())

            assertTrue(loader.unload("native-ok"))
            assertNull(registry.get("native-ok"))
            assertFalse(loader.unload("native-ok"), "second unload of the same module is a no-op")
            loader.unloadAll()
            loader.unloadAll()
            assertEquals(listOf("init:native-ok", "shutdown:native-ok"), events())
        }

    @Test
    fun refusedInitializeIsSkippedAndNeverShutDown() =
        runTest {
            val plugins = pluginDir("native-refuses")
            val loader = NativePluginLoader(registry, logger)

            val result = loader.loadFromDirectories(listOf(plugins), RecordingPluginContext())
            assertEquals(listOf("native-refuses"), result.skipped)
            assertNull(registry.get("native-refuses"))
            loader.unloadAll()
            assertEquals(listOf("init:native-refuses"), events())
        }

    @Test
    fun unloadAllShutsDownEveryLoadedPlugin() =
        runTest {
            val plugins = pluginDir("native-ok", "native-refuses")
            val loader = NativePluginLoader(registry, logger)

            val result = loader.loadFromDirectories(listOf(plugins), RecordingPluginContext())
            assertEquals(listOf("native-ok"), result.loaded)
            loader.unloadAll()
            assertNull(registry.get("native-ok"))
            assertEquals(1, events().count { it == "shutdown:native-ok" })
            assertTrue(events().none { it == "shutdown:native-refuses" })
        }

    @Test
    fun aFileThatIsNotALibraryFailsWithoutStoppingTheLoader() =
        runTest {
            val plugins = pluginDir("native-ok")
            val ext = if (Platform.osFamily == OsFamily.MACOSX) "dylib" else "so"
            Path(plugins, "libbroken.$ext").writeText("not a library")
            val loader = NativePluginLoader(registry, logger)

            val result = loader.loadFromDirectories(listOf(plugins), RecordingPluginContext())
            assertEquals(listOf("native-ok"), result.loaded)
            assertEquals("libbroken.$ext", result.failed.single().path)
            assertTrue(Path(plugins, "libbroken.$ext").exists())
            loader.unloadAll()
        }
}
