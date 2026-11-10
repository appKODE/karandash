package io.karandash.cli.pipeline

import io.karandash.cli.TestFileHelpers
import io.karandash.logger.NoOpLogger
import io.karandash.sdk.Logger
import io.karandash.security.TextRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DumpDirWriterTest {
    @Test
    fun `sanitize keeps subdirs and strips traversal and unsafe characters`() {
        assertEquals("ai/review/plan.json", DumpDirWriter.sanitize("ai/review/plan.json"))
        assertEquals("etc/passwd", DumpDirWriter.sanitize("/../../etc/passwd"))
        assertEquals("a/b_c_d.json", DumpDirWriter.sanitize("a/./b c:d.json"))
        assertEquals("unnamed", DumpDirWriter.sanitize("../.."))
    }

    @Test
    fun `sequenced prefixes only the basename`() {
        assertEquals("0007-mr.json", DumpDirWriter.sequenced("mr.json", 7))
        assertEquals("ai/rule/0012-plan.json", DumpDirWriter.sequenced("ai/rule/plan.json", 12))
    }

    @Test
    fun `writes gitignore and ordered redacted files`() =
        runTest {
            val dir = TestFileHelpers.createTempDir("dump-dir")
            try {
                val writer = assertNotNull(DumpDirWriter.create(Path(dir, "out").toString(), NoOpLogger))
                writer.write("config.json", "{\"apiToken\": \"hunter2-secret-value\"}")
                writer.write("ai/rule/plan.json", "{}")
                writer.write("../escape.json", "x")

                assertEquals("*\n", TestFileHelpers.readFile(Path(dir, "out/.gitignore")))
                val config = TestFileHelpers.readFile(Path(dir, "out/0001-config.json"))
                assertFalse("hunter2" in config, config)
                assertTrue(TestFileHelpers.exists(Path(dir, "out/ai/rule/0002-plan.json")))
                assertTrue(TestFileHelpers.exists(Path(dir, "out/0003-escape.json")))
                assertFalse(TestFileHelpers.exists(Path(dir, "0003-escape.json")))
            } finally {
                TestFileHelpers.deleteTempDir(dir)
            }
        }

    @Test
    fun `redacted JSON stays valid and keeps keys and numeric token counts`() {
        val content = """{"metadata": {"aiTokensUsed": "4993", "apiKey": "hunter2"}, "code": "password=hunter2\" x"}"""

        val redacted = Json.parseToJsonElement(DumpDirWriter.redact(content)).jsonObject

        val metadata = redacted.getValue("metadata").jsonObject
        assertEquals("4993", metadata.getValue("aiTokensUsed").jsonPrimitive.content)
        assertEquals(TextRedactor.REDACTED, metadata.getValue("apiKey").jsonPrimitive.content)
        assertFalse("hunter2" in redacted.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `non-JSON content is redacted as text`() {
        assertFalse("hunter2" in DumpDirWriter.redact("token: hunter2"))
    }

    @Test
    fun `parallel writes into one new subdirectory all land`() =
        runTest {
            val dir = TestFileHelpers.createTempDir("dump-race")
            try {
                val logger = WarnCapturingLogger()
                val writer = assertNotNull(DumpDirWriter.create(Path(dir, "out").toString(), logger))
                withContext(Dispatchers.Default) {
                    (1..PARALLEL_WRITES).map { async { writer.write("ai/rule-$it/deep/plan.json", "{}") } }.awaitAll()
                }

                assertEquals(emptyList(), logger.warnings.value)

                val written = (1..PARALLEL_WRITES).sumOf { SystemFileSystem.list(Path(dir, "out/ai/rule-$it/deep")).size }
                assertEquals(PARALLEL_WRITES, written)
            } finally {
                TestFileHelpers.deleteTempDir(dir)
            }
        }

    private class WarnCapturingLogger : Logger {
        val warnings = MutableStateFlow(emptyList<String>())

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings.update { it + message }
        }

        override fun error(message: String) = Unit
    }

    private companion object {
        const val PARALLEL_WRITES = 64
    }
}
