package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals

/** `stdout` prints the formatted body as-is followed by a newline, and nothing for a blank body. */
class StdoutOutputModuleOutputTest {
    private suspend fun captured(body: String): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            StdoutOutputModule().output(FormattedReport(formatId = "json", body = body), JsonObject(emptyMap()))
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }

    @Test
    fun bodyIsPrintedVerbatim() =
        runTest {
            assertEquals("{\"passed\":true}" + System.lineSeparator(), captured("{\"passed\":true}"))
        }

    @Test
    fun blankBodyPrintsNothing() =
        runTest {
            assertEquals("", captured("  "))
        }
}
