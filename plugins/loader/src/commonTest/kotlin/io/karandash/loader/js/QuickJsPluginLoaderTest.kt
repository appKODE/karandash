package io.karandash.loader.js

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.loader.deleteTestDirectory
import io.karandash.loader.writeText
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickJsPluginLoaderTest {
    private lateinit var registry: ModuleRegistryImpl
    private lateinit var loader: JsPluginLoader
    private lateinit var tempDir: Path
    private var fileCounter = 0
    private val testContext = object : PluginContext {
        override val pluginConfig: JsonElement = JsonObject(emptyMap())

        override fun log(message: String) {}

        override fun warn(message: String) {}

        override fun env(name: String): String? = null

        override val hostVersion: String = "0.1.0-test"
        override val workingDirectory: String = "."
    }

    @BeforeTest
    fun setup() {
        registry = ModuleRegistryImpl()
        loader = JsPluginLoader(registry)
        tempDir = TestFiles.createTestTempDirectory("quickjs-loader-test")
        io.karandash.loader.createDirectories(tempDir)
    }

    @AfterTest
    fun teardown() {
        runBlocking { loader.unloadAll() }
        deleteTestDirectory(tempDir)
    }

    @Test
    fun `loading from empty directory returns empty result`() =
        runTest {
            val result = loader.loadFromDirectories(listOf(tempDir), testContext)

            assertTrue(result.loaded.isEmpty())
            assertTrue(result.failed.isEmpty())
            assertTrue(result.skipped.isEmpty())
        }

    @Test
    fun `loading valid rule plugin succeeds`() =
        runTest {
            writeTestPlugin("valid-rule-plugin.mjs", TestFixtures.VALID_RULE_PLUGIN)

            val result = loader.loadFromDirectories(listOf(tempDir), testContext)

            assertEquals(1, result.loaded.size)
            assertTrue(result.failed.isEmpty())
            assertTrue(result.skipped.isEmpty())

            val module = registry.get(result.loaded[0])
            assertNotNull(module)
            assertEquals("test-rule-plugin", module.id)
            assertEquals(ModuleType.RULE, module.type)
        }

    @Test
    fun `loading valid reporter plugin succeeds`() =
        runTest {
            writeTestPlugin("valid-reporter-plugin.mjs", TestFixtures.VALID_REPORTER_PLUGIN)

            val result = loader.loadFromDirectories(listOf(tempDir), testContext)

            assertEquals(1, result.loaded.size)
            val reporters = registry.getByType(ModuleType.REPORTER)
            assertEquals(1, reporters.size)
            assertEquals("test-reporter-plugin", reporters[0].id)
        }

    @Test
    fun `loading file with syntax error reports failure`() =
        runTest {
            writeTestPlugin("invalid-syntax.js", TestFixtures.INVALID_SYNTAX)

            val result = loader.loadFromDirectories(listOf(tempDir), testContext)

            assertTrue(result.loaded.isEmpty())
            assertEquals(1, result.failed.size)
            assertTrue(result.failed[0].reason.isNotEmpty())
        }

    @Test
    fun `loading plugin with missing fields reports failure`() =
        runTest {
            writeTestPlugin("missing-fields.mjs", TestFixtures.MISSING_FIELDS)

            val result = loader.loadFromDirectories(listOf(tempDir), testContext)

            assertTrue(result.loaded.isEmpty())
            assertEquals(1, result.failed.size)
        }

    @Test
    fun `loading non-eager plugin with init failure still registers module`() =
        runTest {
            writeTestPlugin("init-fails.mjs", TestFixtures.INIT_FAILS)

            val result = loader.loadFromDirectories(listOf(tempDir), testContext)

            assertEquals(1, result.loaded.size)
            assertTrue(result.failed.isEmpty())
            val ruleModules = registry.getByType(ModuleType.RULE)
            assertEquals(1, ruleModules.size)
            assertEquals("init-fails-plugin", ruleModules[0].id)
        }

    @Test
    fun `loading duplicate plugin skips second instance`() =
        runTest {
            writeTestPlugin("valid-rule-plugin.mjs", TestFixtures.VALID_RULE_PLUGIN)

            // Load first time
            val result1 = loader.loadFromDirectories(listOf(tempDir), testContext)
            assertEquals(1, result1.loaded.size)

            // Write same plugin with different name
            writeTestPlugin("duplicate-rule-plugin.mjs", TestFixtures.DUPLICATE_PLUGIN)

            // Load again - both files have the same ID which is already registered,
            // so both are skipped on rediscovery
            val result2 = loader.loadFromDirectories(listOf(tempDir), testContext)
            assertEquals(0, result2.loaded.size)
            assertEquals(2, result2.skipped.size)
        }

    @Test
    fun `loading multiple plugins from same directory succeeds`() =
        runTest {
            writeTestPlugin("valid-rule-plugin.mjs", TestFixtures.VALID_RULE_PLUGIN)
            writeTestPlugin("valid-reporter-plugin.mjs", TestFixtures.VALID_REPORTER_PLUGIN)

            val result = loader.loadFromDirectories(listOf(tempDir), testContext)

            assertEquals(2, result.loaded.size)
            assertTrue(result.failed.isEmpty())

            val ruleModules = registry.getByType(ModuleType.RULE)
            val reporterModules = registry.getByType(ModuleType.REPORTER)
            assertEquals(1, ruleModules.size)
            assertEquals(1, reporterModules.size)
        }

    @Test
    fun `unload removes module from registry`() =
        runTest {
            writeTestPlugin("valid-rule-plugin.mjs", TestFixtures.VALID_RULE_PLUGIN)

            val result = loader.loadFromDirectories(listOf(tempDir), testContext)
            val moduleId = result.loaded[0]

            assertNotNull(registry.get(moduleId))

            assertTrue(loader.unload(moduleId))
            assertNull(registry.get(moduleId))
        }

    @Test
    fun `unload returns false for unknown module`() =
        runTest {
            assertFalse(loader.unload("nonexistent-module"))
        }

    @Test
    fun `unload same module twice returns false second time`() =
        runTest {
            writeTestPlugin("valid-rule-plugin.mjs", TestFixtures.VALID_RULE_PLUGIN)

            val result = loader.loadFromDirectories(listOf(tempDir), testContext)
            val moduleId = result.loaded[0]

            assertTrue(loader.unload(moduleId))
            assertFalse(loader.unload(moduleId))
        }

    @Test
    fun `unloadAll removes all modules from registry`() =
        runTest {
            writeTestPlugin("valid-rule-plugin.mjs", TestFixtures.VALID_RULE_PLUGIN)
            writeTestPlugin("valid-reporter-plugin.mjs", TestFixtures.VALID_REPORTER_PLUGIN)

            val result = loader.loadFromDirectories(listOf(tempDir), testContext)
            assertEquals(2, result.loaded.size)

            loader.unloadAll()

            for (id in result.loaded) {
                assertNull(registry.get(id))
            }
            assertTrue(registry.registeredIds().isEmpty())
        }

    @Test
    fun `unloadAll shuts plugins down in reverse load order despite a failing shutdown`() =
        runTest {
            val shutdownLog = mutableListOf<String>()
            val recordingContext = object : PluginContext by testContext {
                override fun log(message: String) {
                    shutdownLog += message
                }
            }
            for (id in listOf("p1", "p2", "p3")) {
                val failing = if (id == "p2") "throw new Error('boom');" else ""
                writeTestPlugin("$id.mjs", shutdownRecordingPlugin(id, failing))
            }

            val result = loader.loadFromDirectories(listOf(tempDir), recordingContext)
            assertEquals(3, result.loaded.size)

            loader.unloadAll()

            assertEquals(result.loaded.reversed().map { "shutdown:$it" }, shutdownLog)
            assertTrue(registry.registeredIds().isEmpty())
        }

    private fun shutdownRecordingPlugin(
        id: String,
        failing: String,
    ): String =
        """
        export default {
            id: "$id",
            name: "$id",
            version: "1.0.0",
            type: "RULE",
            initialize(context) { return true; },
            contributedRules() { return ["$id-rule"]; },
            validate(ruleId, mergeRequest, config) { return []; },
            shutdown() {
                __karandash_log("shutdown:$id");
                $failing
            }
        };
        """.trimIndent()

    @Test
    fun `unloadAll on empty loader succeeds`() =
        runTest {
            // Should not throw
            loader.unloadAll()
        }

    @Test
    fun `loading from non-existent directory returns empty result`() =
        runTest {
            val nonExistent = Path("$tempDir/no-such-dir")

            val result = loader.loadFromDirectories(listOf(nonExistent), testContext)

            assertTrue(result.loaded.isEmpty())
            assertTrue(result.failed.isEmpty())
            assertTrue(result.skipped.isEmpty())
        }

    @Test
    fun `loading from multiple directories combines results`() =
        runTest {
            val dir1 = Path("$tempDir/dir1")
            val dir2 = Path("$tempDir/dir2")
            io.karandash.loader.createDirectories(dir1)
            io.karandash.loader.createDirectories(dir2)

            // Write different plugins to different directories
            Path("$dir1/valid-rule-plugin.mjs").writeText(TestFixtures.VALID_RULE_PLUGIN)
            Path("$dir2/valid-reporter-plugin.mjs").writeText(TestFixtures.VALID_REPORTER_PLUGIN)

            val result = loader.loadFromDirectories(
                listOf(dir1, dir2),
                testContext
            )

            assertEquals(2, result.loaded.size)
            assertTrue(result.failed.isEmpty())
        }

    // Helper methods

    @Test
    fun `plugin declaring a supported minApiLevel loads`() =
        runTest {
            writeTestPlugin("min-api-ok.mjs", TestFixtures.VALID_RULE_PLUGIN.replace("type: \"RULE\",", "type: \"RULE\", minApiLevel: 1,"))

            val result = loader.loadFromDirectories(listOf(tempDir), testContext)

            assertEquals(listOf("test-rule-plugin"), result.loaded)
            assertTrue(result.failed.isEmpty())
        }

    @Test
    fun `plugin requiring a newer API level is rejected with a clear message`() =
        runTest {
            writeTestPlugin(
                "min-api-new.mjs",
                TestFixtures.VALID_RULE_PLUGIN.replace("type: \"RULE\",", "type: \"RULE\", minApiLevel: 999,")
            )

            val result = loader.loadFromDirectories(listOf(tempDir), testContext)

            assertTrue(result.loaded.isEmpty())
            assertNull(registry.get("test-rule-plugin"))
            val reason = result.failed.single().reason
            assertTrue("requires karandash plugin API level 999" in reason, reason)
            assertTrue("Upgrade karandash" in reason, reason)
        }

    private fun writeTestPlugin(
        filename: String,
        content: String
    ) {
        val pluginFile = Path("$tempDir/$filename")
        pluginFile.writeText(content)
    }
}
