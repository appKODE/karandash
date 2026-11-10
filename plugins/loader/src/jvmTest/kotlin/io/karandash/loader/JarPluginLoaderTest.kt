package io.karandash.loader

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.loader.jar.JarPluginLoader
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JarPluginLoaderTest {
    private lateinit var tempDir: File
    private val testContext = object : PluginContext {
        override val pluginConfig: JsonElement = JsonObject(emptyMap())

        override fun log(message: String) {}

        override fun warn(message: String) {}

        override fun env(name: String): String? = null

        override val hostVersion: String = "0.1.0-test"
        override val workingDirectory: String = "."
    }

    @Before
    fun setup() {
        tempDir = java.io.File(TestFiles.createTestTempDirectory("jar-loader-test").toString())
    }

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `loading from empty directory returns empty result`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val loader = JarPluginLoader(registry)

            val result = loader.loadFromDirectories(listOf(tempDir.toPath().toKotlinPath()), testContext)

            assertTrue(result.loaded.isEmpty())
            assertTrue(result.failed.isEmpty())
            assertTrue(result.skipped.isEmpty())
        }

    @Test
    fun `loading non-existent file reports failure`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val loader = JarPluginLoader(registry)
            val fakeJar = File(tempDir, "nonexistent.jar")

            val result = loader.loadJar(fakeJar.toPath().toKotlinPath(), testContext)

            assertTrue(result.loaded.isEmpty())
            assertEquals(1, result.failed.size)
            assertTrue(result.failed[0].reason.contains("does not exist"))
        }

    @Test
    fun `loading invalid jar reports no modules found`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val loader = JarPluginLoader(registry)

            // Create a fake JAR (just an empty file - not a valid ZIP)
            val fakeJar = File(tempDir, "invalid.jar")
            fakeJar.writeText("not a real jar")

            val result = loader.loadJar(fakeJar.toPath().toKotlinPath(), testContext)

            assertTrue(result.loaded.isEmpty())
            assertEquals(1, result.failed.size)
        }

    @Test
    fun `unload returns false for unknown module`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val loader = JarPluginLoader(registry)

            assertFalse(loader.unload("nonexistent"))
        }

    @Test
    fun `unloadAll on empty loader succeeds`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val loader = JarPluginLoader(registry)

            // Should not throw
            loader.unloadAll()
        }

    @Test
    fun `pluginCache returns the cache instance`() {
        val registry = ModuleRegistryImpl()
        val cache = PluginCache()
        val loader = JarPluginLoader(registry, cache)

        assertSame(loader.pluginCache(), cache)
    }

    @Test
    fun `loading from non-existent directory returns empty result`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val loader = JarPluginLoader(registry)
            val nonExistent = File(tempDir, "no-such-dir")

            val result = loader.loadFromDirectories(listOf(nonExistent.toPath().toKotlinPath()), testContext)

            assertTrue(result.loaded.isEmpty())
            assertTrue(result.failed.isEmpty())
            assertTrue(result.skipped.isEmpty())
        }
}
