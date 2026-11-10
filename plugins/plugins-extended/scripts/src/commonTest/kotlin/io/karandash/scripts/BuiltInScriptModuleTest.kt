package io.karandash.scripts

import io.karandash.api.ModuleType
import io.karandash.platform.createDirectories
import io.karandash.platform.writeText
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltInScriptModuleTest {
    @Test
    fun moduleMetadata() {
        val module = BuiltInScriptModule()
        assertEquals("io.karandash.script", module.id)
        assertEquals("Script", module.name)
        assertEquals("1.7.0", module.version)
        assertEquals(ModuleType.SCRIPT, module.type)
    }

    @Test
    fun executeWithEmptyScriptsReturnsEmpty() =
        runTest {
            val module = BuiltInScriptModule()
            val context = TestPluginContext()
            val mr = TestFixtures.minimalMr()
            val findings = module.execute(emptyList(), mr, context)
            assertTrue(findings.isEmpty())
        }

    @Test
    fun executeWithMissingFileReturnsEmpty() =
        runTest {
            val module = BuiltInScriptModule()
            val context = TestPluginContext(workingDir = "/nonexistent")
            val mr = TestFixtures.minimalMr()
            val scripts = listOf(JsonObject(mapOf("path" to JsonPrimitive("missing.js"))))
            val findings = module.execute(scripts, mr, context)
            assertTrue(findings.isEmpty())
        }

    @Test
    fun `an unreadable script entry is warned about and the valid ones still report`() =
        runTest {
            val dir = TestFiles.createTestTempDirectory("scripts-partial")
            Path(dir, "a.js").writeText("karandash.warn('from a');")
            Path(dir, "b.js").writeText("karandash.warn('from b');")
            createDirectories(Path(dir, "bad.js"))
            val context = TestPluginContext(workingDir = dir.toString())
            val scripts = listOf("a.js", "bad.js", "b.js").map { JsonObject(mapOf("path" to JsonPrimitive(it))) }

            val findings = withContext(Dispatchers.Default) { BuiltInScriptModule().execute(scripts, TestFixtures.minimalMr(), context) }

            assertEquals(listOf("from a", "from b"), findings.map { it.message })
            assertTrue(context.warnings.any { "'bad.js'" in it }, "${context.warnings}")
        }
}
