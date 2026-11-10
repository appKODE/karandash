package io.karandash.subprocess

import io.karandash.platform.createDirectories
import io.karandash.platform.currentWorkingDirectory
import io.karandash.platform.writeText
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** the bridge JAR lookup goes through the file helpers and yields an absolute classpath entry. */
class BridgeJarLookupTest {
    private val home = TestFiles.createTestTempDirectory("bridge-home")

    @Test
    fun `default bridge jar is found and returned as an absolute path`() {
        val lib = Path(home, ".karandash/lib").also { createDirectories(it) }
        Path(lib, "karandash-jar-bridge.jar").writeText("jar")

        val found = JarBridgeClient.resolveBridgeJar(envPath = null, home = home.toString())

        assertTrue(found != null && found.startsWith("/") && found.endsWith("karandash-jar-bridge.jar"), "$found")
    }

    @Test
    fun `missing default bridge jar yields null`() {
        createDirectories(home)

        assertNull(JarBridgeClient.resolveBridgeJar(envPath = null, home = home.toString()))
    }

    @Test
    fun `relative env path is made absolute against the working directory`() {
        val found = JarBridgeClient.resolveBridgeJar(envPath = "no-such-bridge.jar", home = null)

        assertEquals(Path(currentWorkingDirectory(), "no-such-bridge.jar").toString(), found)
    }
}
