package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** One behaviour test per `filesystem` output key. */
class FilesystemKeyBehaviourTest {
    @Test
    fun `outputFile is the path the report body is written to`() =
        runTest {
            val target = Path(TestFiles.createTestTempDirectory("karandash-fs"), "nested", "report.txt")

            FilesystemOutputModule().output(
                FormattedReport(formatId = "json", body = "hello report"),
                JsonObject(mapOf("outputFile" to JsonPrimitive(target.toString()))),
            )

            assertEquals("hello report", SystemFileSystem.source(target).buffered().use { it.readString() })
        }
}
