package io.karandash.loader.jar

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The bridge entry point reads one JSON request from stdin and answers on stdout. */
class BridgeMainSmokeTest {
    @Test
    fun `bridge main answers an unknown method with an error response`() {
        val (exit, output) = runBridge("{\"method\":\"ci10-probe\"}\n")
        assertEquals(0, exit, output)
        assertTrue(output.contains("Unknown method: ci10-probe"), output)
    }

    @Test
    fun `bridge main reports empty input`() {
        val (exit, output) = runBridge("")
        assertEquals(0, exit, output)
        assertTrue(output.contains("No input"), output)
    }

    private fun runBridge(stdin: String): Pair<Int, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process =
            ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "io.karandash.loader.jar.BridgeMainKt")
                .redirectErrorStream(true)
                .start()
        process.outputStream.use { it.write(stdin.encodeToByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "bridge did not exit: $output")
        return process.exitValue() to output
    }
}
