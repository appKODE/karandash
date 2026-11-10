@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package io.karandash.loader.native

import io.karandash.api.ModuleRegistryImpl
import io.karandash.loader.RecordingLogger
import io.karandash.loader.bridge.host.HostTestContext
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.copyTo
import io.karandash.platform.createDirectories
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** A v2 C guest (`src/nativeTest/c/host_plugin.c`) reaches the host's log and cache through `karandash_set_host`. */
class NativeHostChannelTest {
    @Test
    fun aV2GuestLogsAndCachesThroughTheHostChannel() =
        runTest {
            val built = checkNotNull(PlatformEnvironment.getEnv("LIFECYCLE_PLUGIN_DIR")) { "LIFECYCLE_PLUGIN_DIR not set by Gradle" }
            val plugins = Path(TestFiles.createTestTempDirectory("native-host"), "plugins").also { createDirectories(it) }
            val lib = if (Platform.osFamily == OsFamily.MACOSX) "libnative-host.dylib" else "libnative-host.so"
            Path(built, lib).copyTo(Path(plugins, lib))
            val context = HostTestContext()
            val loader = NativePluginLoader(ModuleRegistryImpl(), RecordingLogger())

            val result = loader.loadFromDirectories(listOf(plugins), context)

            assertEquals(listOf("native-host"), result.loaded, "$result")
            assertEquals(listOf("hello", "cache:hit"), context.logs)
            assertEquals("v", context.cache.entries["native-host:k"])
            loader.unloadAll()
        }
}
