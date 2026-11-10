package io.karandash.cli.compat

import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** JVM-only: swaps the global `System.out`/`System.err`, which native has no equivalent for. */
internal object ConsoleCapture {
    /** Runs [block] with `System.out`/`System.err` redirected; returns its result plus what it printed to each. */
    suspend fun <T> capture(block: suspend () -> T): Triple<T, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        val result = try {
            block()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        return Triple(result, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }
}
