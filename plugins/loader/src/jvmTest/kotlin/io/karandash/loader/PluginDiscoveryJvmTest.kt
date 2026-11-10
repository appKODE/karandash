package io.karandash.loader

import io.karandash.loader.jar.defaultPluginDirAsFile
import io.karandash.loader.jar.discoverJars
import io.karandash.testfixtures.TestFiles
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM-specific tests for JAR discovery.
 * Native library and JS module tests are in commonTest.
 */
class PluginDiscoveryJvmTest {
    private lateinit var tempDir: File

    @Before
    fun setup() {
        tempDir = java.io.File(TestFiles.createTestTempDirectory("plugin-discovery-test").toString())
    }

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `discovers JAR files in directory`() {
        File(tempDir, "plugin-a.jar").createNewFile()
        File(tempDir, "plugin-b.jar").createNewFile()
        File(tempDir, "readme.txt").createNewFile()

        val jars = PluginDiscovery.discoverJars(tempDir)

        assertEquals(2, jars.size)
        assertEquals("plugin-a.jar", jars[0].name)
        assertEquals("plugin-b.jar", jars[1].name)
    }

    @Test
    fun `returns empty list for non-existent directory`() {
        val nonExistent = File(tempDir, "does-not-exist")
        val jars = PluginDiscovery.discoverJars(nonExistent)

        assertTrue(jars.isEmpty())
    }

    @Test
    fun `returns empty list for empty directory`() {
        val jars = PluginDiscovery.discoverJars(tempDir)

        assertTrue(jars.isEmpty())
    }

    @Test
    fun `ignores non-jar files`() {
        File(tempDir, "not-a-jar.txt").createNewFile()
        File(tempDir, "also-not.zip").createNewFile()
        File(tempDir, "plugin.jar").createNewFile()

        val jars = PluginDiscovery.discoverJars(tempDir)

        assertEquals(1, jars.size)
        assertEquals("plugin.jar", jars[0].name)
    }

    @Test
    fun `ignores directories named dot-jar`() {
        File(tempDir, "fake.jar").mkdirs()
        File(tempDir, "real.jar").createNewFile()

        val jars = PluginDiscovery.discoverJars(tempDir)

        assertEquals(1, jars.size)
        assertEquals("real.jar", jars[0].name)
    }

    @Test
    fun `discovers from multiple directories`() {
        val dir1 = File(tempDir, "dir1").apply { mkdirs() }
        val dir2 = File(tempDir, "dir2").apply { mkdirs() }
        File(dir1, "a.jar").createNewFile()
        File(dir2, "b.jar").createNewFile()

        val jars = PluginDiscovery.discoverJars(listOf(dir1, dir2))

        assertEquals(2, jars.size)
    }

    @Test
    fun `results are sorted by name`() {
        File(tempDir, "z-plugin.jar").createNewFile()
        File(tempDir, "a-plugin.jar").createNewFile()
        File(tempDir, "m-plugin.jar").createNewFile()

        val jars = PluginDiscovery.discoverJars(tempDir)

        assertEquals(3, jars.size)
        assertEquals("a-plugin.jar", jars[0].name)
        assertEquals("m-plugin.jar", jars[1].name)
        assertEquals("z-plugin.jar", jars[2].name)
    }

    @Test
    fun `defaultPluginDirAsFile returns expected directory`() {
        val dir = PluginDiscovery.defaultPluginDirAsFile
        assertTrue(
            dir.path.endsWith(".karandash/plugins") ||
                dir.path.endsWith(".karandash${File.separator}plugins")
        )
    }
}
