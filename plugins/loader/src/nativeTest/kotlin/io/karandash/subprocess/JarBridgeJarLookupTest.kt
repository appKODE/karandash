package io.karandash.subprocess

import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.createDirectories
import io.karandash.platform.writeText
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a native host finds the jar-bridge JAR: env var, `~/.karandash/lib`, then next to the executable.
 *
 */
class JarBridgeJarLookupTest {
    private val dir = TestFiles.createTestTempDirectory("jar-lookup").also { createDirectories(it) }
    private val home = Path(dir, "home").also { createDirectories(it) }
    private val bin = Path(dir, "app").also { createDirectories(it) }
    private val env = { name: String -> if (name == "HOME") home.toString() else null }

    @AfterTest
    fun cleanup() = TestFiles.deleteRecursively(dir)

    @Test
    fun `the bridge jar next to the executable is found`() {
        val jar = Path(bin, "karandash-jar-bridge.jar").also { it.writeText("jar") }

        assertEquals(jar.toString(), JarBridgeClient.findBridgeJar(env, Path(bin, "karandash").toString()))
    }

    @Test
    fun `the home lib jar wins over the executable dir and a missing jar is null`() {
        assertNull(JarBridgeClient.findBridgeJar(env, Path(bin, "karandash").toString()))

        val lib = Path(home, ".karandash", "lib").also { createDirectories(it) }
        val jar = Path(lib, "karandash-jar-bridge.jar").also { it.writeText("jar") }
        Path(bin, "karandash-jar-bridge.jar").writeText("jar")
        assertEquals(jar.toString(), JarBridgeClient.findBridgeJar(env, Path(bin, "karandash").toString()))
    }

    @Test
    fun `the native binary knows its own path`() {
        val self = assertNotNull(PlatformEnvironment.executablePath())
        assertTrue(SystemFileSystem.exists(Path(self)), self)
    }
}
