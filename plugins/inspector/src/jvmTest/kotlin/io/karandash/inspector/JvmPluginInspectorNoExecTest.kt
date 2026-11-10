package io.karandash.inspector

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Constructed by plugin loading; the inspector must never get here. */
class TripwireModule : Module {
    init {
        constructed = true
    }

    override val id: String = "tripwire"
    override val name: String = "Tripwire"
    override val version: String = "9.9.9"
    override val type: ModuleType = ModuleType.RULE

    override suspend fun initialize(context: PluginContext): Boolean {
        initialized = true
        return true
    }

    companion object {
        @Volatile
        var constructed = false

        @Volatile
        var initialized = false
    }
}

/** Install-time inspection reads the JAR statically and runs no plugin code. */
class JvmPluginInspectorNoExecTest {
    private val serviceEntry = "META-INF/services/io.karandash.api.Module"
    private val className = TripwireModule::class.java.name

    private fun jar(
        withServices: Boolean = true,
        withClass: Boolean = true,
        attributes: Map<String, String> = mapOf("Plugin-Id" to "tripwire", "Plugin-Version" to "9.9.9"),
        services: String = "$className\n",
    ): File {
        val file = java.nio.file.Paths
            .get(TestFiles.createTestTempDirectory("inspector").toString())
            .resolve("tripwire.jar")
            .toFile()
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            attributes.forEach { (key, value) -> mainAttributes.putValue(key, value) }
        }
        JarOutputStream(file.outputStream(), manifest).use { out ->
            if (withServices) {
                out.putNextEntry(JarEntry(serviceEntry))
                out.write(services.encodeToByteArray())
                out.closeEntry()
            }
            if (withClass) {
                val classPath = className.replace('.', '/') + ".class"
                val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream(classPath)) { classPath }.readBytes()
                out.putNextEntry(JarEntry(classPath))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `verify and metadata never construct or initialize plugin modules`() =
        runTest {
            val inspector = JvmPluginInspector()
            val file = jar()

            assertNull(inspector.verifyFile(file.path, "jar"))
            val metadata = inspector.readMetadata(file.path)

            assertEquals("tripwire" to "9.9.9", metadata?.id to metadata?.version)
            assertFalse(TripwireModule.constructed, "plugin class was instantiated during inspection")
            assertFalse(TripwireModule.initialized, "plugin module was initialized during inspection")
        }

    @Test
    fun `jar without module registration or with a missing class is rejected`() =
        runTest {
            val inspector = JvmPluginInspector()

            assertNotNull(inspector.verifyFile(jar(withServices = false).path, "jar"))
            assertNotNull(inspector.verifyFile(jar(withClass = false).path, "jar"))
            assertNull(inspector.readMetadata(jar(withServices = false).path))
        }

    @Test
    fun `metadata falls back to implementation attributes and then to the file name`() =
        runTest {
            val inspector = JvmPluginInspector()
            val impl = jar(attributes = mapOf("Implementation-Title" to "impl-id", "Implementation-Version" to "1.2"))
            val bare = jar(attributes = emptyMap())

            val implMeta = inspector.readMetadata(impl.path)
            val bareMeta = inspector.readMetadata(bare.path)

            assertEquals(listOf("impl-id", "impl-id", "1.2"), listOf(implMeta?.id, implMeta?.name, implMeta?.version))
            assertEquals(listOf("tripwire", "tripwire", "unknown"), listOf(bareMeta?.id, bareMeta?.name, bareMeta?.version))
        }

    @Test
    fun `service file comments and blank lines are ignored`() =
        runTest {
            val file = jar(services = "# registered modules\n\n$className # tripwire\n")
            assertNull(JvmPluginInspector().verifyFile(file.path, "jar"))
        }

    @Test
    fun `a corrupt jar is reported as invalid and has no metadata`() =
        runTest {
            val file = java.nio.file.Paths
                .get(TestFiles.createTestTempDirectory("inspector").toString())
                .resolve("corrupt.jar")
                .toFile()
                .apply { writeText("not a zip") }
            val inspector = JvmPluginInspector()

            assertTrue(inspector.verifyFile(file.path, "jar").orEmpty().startsWith("Not a valid JAR file: corrupt.jar"))
            assertNull(inspector.readMetadata(file.path))
        }

    @Test
    fun `non-jar plugins are judged by existence and size only`() =
        runTest {
            val dir = java.nio.file.Paths
                .get(TestFiles.createTestTempDirectory("inspector").toString())
                .toFile()
            val wasm = File(dir, "rule.wasm").apply { writeText("bytes") }
            val empty = File(dir, "empty.js").apply { createNewFile() }
            val inspector = JvmPluginInspector()

            assertEquals(PluginFileMetadata(id = "rule", name = "rule", version = "unknown"), inspector.readMetadata(wasm.path))
            assertNull(inspector.readMetadata(empty.path))
            assertNull(inspector.readMetadata(dir.path))
            assertNull(inspector.readMetadata(File(dir, "missing.wasm").path))
            assertNull(inspector.verifyFile(wasm.path, "wasm"))
            assertEquals("File is empty: empty.js", inspector.verifyFile(empty.path, "js"))
        }

    @Test
    fun `a non-empty script takes its id from the file name and probe falls back to it`() =
        runTest {
            val inspector = createPluginInspector()
            val script = File(
                java.nio.file.Paths
                    .get(TestFiles.createTestTempDirectory("script").toString())
                    .toFile(),
                "my-rule.js"
            ).apply {
                writeText("export {}")
            }

            val expected = PluginFileMetadata(id = "my-rule", name = "my-rule", version = "unknown")
            assertEquals(expected, inspector.readMetadata(script.path))
            assertEquals(expected, inspector.probeMetadata(script.path))
            assertEquals(setOf("dylib", "so", "dll"), inspector.nativeLibraryExtensions())
            inspector.shutdown()
        }
}
