package io.karandash.loader.jar

import io.karandash.api.Module
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.loader.PluginCache
import io.karandash.logger.Logger
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shared shutdown trace for the ServiceLoader-instantiated modules below. */
object LifecycleTrace {
    val shutdowns = mutableListOf<String>()
}

abstract class TraceModule(
    override val id: String,
) : Module {
    override val name: String get() = id
    override val version: String get() = "1.0"
    override val type: ModuleType get() = ModuleType.RULE

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun shutdown() {
        LifecycleTrace.shutdowns += id
    }
}

class TraceModuleA : TraceModule("trace-a")

class TraceModuleB : TraceModule("trace-b") {
    override suspend fun shutdown() {
        super.shutdown()
        error("boom")
    }
}

class TraceModuleC : TraceModule("trace-c")

class JarPluginLoaderLifecycleTest {
    private lateinit var tempDir: File
    private val warnings = mutableListOf<String>()
    private val logger = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }
    private val context = object : PluginContext {
        override val pluginConfig: JsonElement = JsonObject(emptyMap())
        override val hostVersion: String = "0.1.0-test"
        override val workingDirectory: String = "."

        override fun env(name: String): String? = null
    }

    @Before
    fun setup() {
        LifecycleTrace.shutdowns.clear()
        tempDir = java.io.File(TestFiles.createTestTempDirectory("jar-lifecycle-test").toString())
    }

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private fun servicesJar(): File {
        val jar = File(tempDir, "trace.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/services/${Module::class.java.name}"))
            val classes = listOf(TraceModuleA::class, TraceModuleB::class, TraceModuleC::class)
            out.write(classes.joinToString("\n") { it.java.name }.toByteArray())
            out.closeEntry()
        }
        return jar
    }

    @Test
    fun `unloadAll shuts down in reverse load order and isolates a failing shutdown`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val cache = PluginCache()
            val loader = JarPluginLoader(registry, cache, logger)
            val result = loader.loadJars(listOf(servicesJar()), context)
            assertEquals(listOf("trace-a", "trace-b", "trace-c"), result.loaded)

            loader.unloadAll()

            assertEquals(listOf("trace-c", "trace-b", "trace-a"), LifecycleTrace.shutdowns)
            assertTrue(warnings.single().contains("'trace-b' shutdown failed: boom"))
            assertTrue(registry.registeredIds().isEmpty())
            assertEquals(0, cache.size())
        }

    @Test
    fun `unload shuts the module down exactly once`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val loader = JarPluginLoader(registry, PluginCache(), logger)
            loader.loadJars(listOf(servicesJar()), context)

            assertTrue(loader.unload("trace-a"))
            loader.unloadAll()

            assertEquals(listOf("trace-a", "trace-c", "trace-b"), LifecycleTrace.shutdowns)
        }
}
