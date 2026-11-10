package io.karandash.diagnostics

import kotlin.test.Test
import kotlin.test.assertTrue

/** JVM CPU time covers the whole process, not one thread. */
class PlatformDiagnosticsJvmTest {
    @Test
    fun `cpu time covers all threads of the process`() {
        val worker = Thread {
            val end = System.nanoTime() + 300_000_000L
            var x = 0L
            while (System.nanoTime() < end) x++
            check(x >= 0)
        }
        worker.start()
        worker.join()
        val snapshot = PlatformDiagnostics.captureSnapshot(0)
        assertTrue(snapshot.cpuUserTimeMs >= 200, "cpuUserTimeMs=${snapshot.cpuUserTimeMs}")
    }
}
