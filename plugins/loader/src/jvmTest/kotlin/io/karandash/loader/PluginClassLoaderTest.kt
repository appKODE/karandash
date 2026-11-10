package io.karandash.loader

import io.karandash.loader.jar.PluginClassLoader
import io.karandash.testfixtures.TestFiles
import org.junit.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginClassLoaderTest {
    @Test
    fun `creates classloader with jar URL`() {
        val tempFile = File(TestFiles.createTestTempDirectory("test-plugin").toString(), "test-plugin.jar").apply { createNewFile() }
        tempFile.deleteOnExit()

        val cl = PluginClassLoader(tempFile.toURI().toURL())

        assertNotNull(cl)
        assertTrue(cl.label.endsWith(".jar"))
        cl.close()
    }

    @Test
    fun `toString includes jar name`() {
        val tempFile = File(TestFiles.createTestTempDirectory("my-plugin").toString(), "my-plugin.jar").apply { createNewFile() }
        tempFile.deleteOnExit()

        val cl = PluginClassLoader(tempFile.toURI().toURL())

        assertTrue(cl.toString().contains("PluginClassLoader("))
        assertTrue(cl.toString().contains(".jar"))
        cl.close()
    }

    @Test
    fun `can load classes from parent classloader`() {
        val tempFile = File(TestFiles.createTestTempDirectory("test-plugin").toString(), "test-plugin.jar").apply { createNewFile() }
        tempFile.deleteOnExit()

        val cl = PluginClassLoader(tempFile.toURI().toURL())

        // Should be able to load SDK classes from parent
        val moduleClass = cl.loadClass("io.karandash.api.Module")
        assertNotNull(moduleClass)
        cl.close()
    }
}
