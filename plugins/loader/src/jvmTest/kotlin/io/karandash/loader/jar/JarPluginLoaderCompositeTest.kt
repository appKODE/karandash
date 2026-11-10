package io.karandash.loader.jar

import io.karandash.api.Module
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.loader.PluginCache
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.sdk.NoOpLogger
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** One class serving two module types, loaded through ServiceLoader. */
class RuleAndReporterModule :
    RuleModule,
    ReporterModule {
    override val id: String = "rule-and-reporter"
    override val name: String = id
    override val version: String = "1.0"
    override val type: ModuleType = ModuleType.RULE
    override val formatId: String = "dual"

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun contributedRules(): Set<String> = setOf("dual-rule")

    override suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
    ): List<Finding> = emptyList()

    override suspend fun report(
        findings: List<Finding>,
        passed: Boolean,
        config: JsonElement,
    ): String = "reported ${findings.size}"
}

class JarPluginLoaderCompositeTest {
    private lateinit var tempDir: File

    private val context = object : PluginContext {
        override val pluginConfig: JsonElement = JsonObject(emptyMap())
        override val hostVersion: String = "0.1.0-test"
        override val workingDirectory: String = "."

        override fun env(name: String): String? = null
    }

    @Before
    fun setup() {
        tempDir = java.io.File(TestFiles.createTestTempDirectory("jar-composite-test").toString())
    }

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private fun servicesJar(): File {
        val jar = File(tempDir, "dual.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/services/${Module::class.java.name}"))
            out.write(RuleAndReporterModule::class.java.name.toByteArray())
            out.closeEntry()
        }
        return jar
    }

    @Test
    fun `a multi-interface module registers under every type it serves`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val loader = JarPluginLoader(registry, PluginCache(), NoOpLogger).apply { keyManager = null }

            assertEquals(listOf("rule-and-reporter"), loader.loadJars(listOf(servicesJar()), context).loaded)

            val rule = registry.ruleModules().single()
            val reporter = registry.reporters().single()
            assertSame(rule, registry.get("rule-and-reporter"))
            assertEquals(ModuleType.REPORTER, reporter.type)
            assertEquals("dual", reporter.formatId)
            assertEquals("reported 0", reporter.report(emptyList(), passed = true, config = JsonObject(emptyMap())))
            assertTrue(registry.hasRule("dual-rule"))

            loader.unload("rule-and-reporter")
            assertTrue(registry.reporters().isEmpty())
            assertNull(registry.get("rule-and-reporter"))
        }
}
