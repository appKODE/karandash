package io.karandash.cliapp

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The JVM entry point starts in a fresh JVM on the real classpath and answers `--version`. */
class MainSmokeTest {
    @Test
    fun `main prints the version and exits 0`() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process =
            ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "io.karandash.cliapp.MainKt", "--version")
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "main did not exit: $output")
        assertEquals(0, process.exitValue(), output)
        assertTrue(output.contains("karandash", ignoreCase = true), output)
    }
}
