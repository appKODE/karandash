package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.errors.ExitCodes
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `sign` rejects bad input with exit 2 and an unusable key with exit 6, never writing a signature. */
class SignCommandTest {
    private val tempDir = TestFileHelpers.createTempDir("karandash-sign-test")
    private val jar = Path(tempDir, "test-plugin.jar").also { TestFileHelpers.writeFile(it, "PK fake jar") }
    private val key = Path(tempDir, "private.key").also { TestFileHelpers.writeFile(it, "not a PKCS8 key") }

    @AfterTest
    fun cleanup() = TestFileHelpers.deleteTempDir(tempDir)

    private fun sign(vararg args: String) = SignCommand().test(args.toList())

    @Test
    fun `non-jar input is a config error`() {
        val txt = Path(tempDir, "plugin.txt").also { TestFileHelpers.writeFile(it, "x") }
        val result = sign(txt.toString(), "--key", key.toString())
        assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode)
        assertTrue("must have .jar extension" in result.stderr, result.stderr)
    }

    @Test
    fun `missing jar is a config error`() {
        val result = sign(Path(tempDir, "missing.jar").toString(), "--key", key.toString())
        assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode)
        assertTrue("JAR file not found" in result.stderr, result.stderr)
    }

    @Test
    fun `missing key is a config error`() {
        val result = sign(jar.toString(), "--key", Path(tempDir, "missing.key").toString())
        assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode)
        assertTrue("Key file not found" in result.stderr, result.stderr)
    }

    @Test
    fun `unusable key is an execution error and writes no signature`() {
        val result = sign(jar.toString(), "--key", key.toString())
        assertEquals(ExitCodes.EXECUTION_ERROR, result.statusCode, result.output)
        assertTrue("Failed to sign JAR" in result.stderr, result.stderr)
        assertFalse(SystemFileSystem.exists(Path("$jar.sig")))
    }

    @Test
    fun `key option is required`() {
        val result = sign(jar.toString())
        assertEquals(1, result.statusCode, result.output)
        assertTrue("--key" in result.stderr, result.stderr)
    }
}
