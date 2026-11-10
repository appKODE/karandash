package io.karandash.scripts

import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScriptRoutingAndConfigTest {
    private fun entry(vararg pairs: Pair<String, Any>) =
        JsonObject(pairs.associate { (k, v) -> k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) })

    @Test
    fun `module reads the script from the working directory and runs it in the sandbox`() =
        runTest {
            val dir = TestFiles.createTestTempDirectory("karandash-scripts")
            SystemFileSystem.createDirectories(dir)
            SystemFileSystem.sink(Path(dir, "check.js")).buffered().use { it.writeString("karandash.warn('from file');") }
            val context = TestPluginContext(workingDir = dir.toString())

            val findings =
                withContext(Dispatchers.Default) {
                    BuiltInScriptModule().execute(
                        listOf(entry("path" to "check.js"), entry("path" to "check.js", "mode" to "sandbox")),
                        TestFixtures.minimalMr(),
                        context,
                    )
                }

            SystemFileSystem.delete(Path(dir, "check.js"))
            SystemFileSystem.delete(dir)
            assertEquals(listOf("from file", "from file"), findings.map { it.message })
            assertTrue(BuiltInScriptModule().initialize(context))
        }

    @Test
    fun `script path escaping the working directory is rejected`() =
        runTest {
            val parent = TestFiles.createTestTempDirectory("karandash-scripts")
            val project = Path(parent, "project")
            SystemFileSystem.createDirectories(project)
            SystemFileSystem.sink(Path(parent, "outside.js")).buffered().use { it.writeString("karandash.warn('escaped');") }
            SystemFileSystem.sink(Path(project, "inside.js")).buffered().use { it.writeString("karandash.warn('inside');") }
            val context = TestPluginContext(workingDir = project.toString())

            val findings =
                withContext(Dispatchers.Default) {
                    BuiltInScriptModule().execute(
                        listOf(entry("path" to "../outside.js"), entry("path" to "./inside.js")),
                        TestFixtures.minimalMr(),
                        context,
                    )
                }

            SystemFileSystem.delete(Path(parent, "outside.js"))
            SystemFileSystem.delete(Path(project, "inside.js"))
            SystemFileSystem.delete(project)
            SystemFileSystem.delete(parent)
            assertEquals(listOf("inside"), findings.map { it.message })
            assertEquals(listOf("Script path '../outside.js' is outside the working directory"), context.warnings)
        }

    @Test
    fun `malformed entries are rejected with a clear message`() {
        val notObject = assertFailsWith<IllegalArgumentException> { ScriptConfigParser.parse(listOf(JsonArray(emptyList()))) }
        val numericPath =
            assertFailsWith<IllegalArgumentException> { ScriptConfigParser.parse(listOf(JsonObject(mapOf("path" to JsonPrimitive(1))))) }

        assertTrue(notObject.message.orEmpty().startsWith("Script entry must be an object"))
        assertEquals("Script entry missing required 'path' field", numericPath.message)
    }

    @Test
    fun `non string mode and non duration timeout fall back to defaults`() {
        val parsed = ScriptConfigParser.parse(listOf(entry("path" to "a.js", "mode" to true, "timeout" to true))).single()

        assertEquals(ScriptEntryConfig(path = "a.js"), parsed)
    }
}
