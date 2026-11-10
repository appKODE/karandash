package io.karandash.cli.detect

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.ReporterModule
import io.karandash.api.platform.ProviderDetectionScore
import io.karandash.detect.SmartReporterDefaults
import io.karandash.models.finding.Finding
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmartReporterDefaultsTest {
    private fun makeReporter(
        moduleId: String,
        format: String,
        isDefaultReporter: Boolean = false,
        isDefaultInteractive: Boolean = false,
        isDefaultCI: Boolean = false,
    ): ReporterModule =
        object : ReporterModule {
            override val id = moduleId
            override val name = moduleId
            override val version = "1.0"
            override val type get() = ModuleType.REPORTER
            override val formatId = format
            override val isDefaultReporter = isDefaultReporter
            override val isDefaultInteractive = isDefaultInteractive
            override val isDefaultCI = isDefaultCI

            override suspend fun initialize(context: PluginContext) = true

            override suspend fun report(
                findings: List<Finding>,
                passed: Boolean,
                config: JsonElement,
            ): String? = null
        }

    private val ciDetection = ProviderDetectionScore(
        providerId = "gitlab",
        score = 0.9,
        source = "ci:gitlab",
    )

    @Test
    fun `selects CI default reporter when detection present`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(makeReporter("console", "console", isDefaultInteractive = true))
            registry.register(makeReporter("markdown", "markdown", isDefaultCI = true))

            val defaults = SmartReporterDefaults(registry)
            assertEquals("markdown", defaults.resolveDefaultFormat(ciDetection))
        }

    @Test
    fun `selects interactive default reporter when no detection`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(makeReporter("console", "console", isDefaultInteractive = true))
            registry.register(makeReporter("markdown", "markdown", isDefaultCI = true))

            val defaults = SmartReporterDefaults(registry)
            assertEquals("console", defaults.resolveDefaultFormat(null))
        }

    @Test
    fun `falls back to default reporter when CI flag not set`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(makeReporter("console", "console", isDefaultReporter = true))
            registry.register(makeReporter("json", "json"))

            val defaults = SmartReporterDefaults(registry)
            assertEquals("console", defaults.resolveDefaultFormat(ciDetection))
        }

    @Test
    fun `returns null when no reporters registered`() {
        val registry = ModuleRegistryImpl()
        val defaults = SmartReporterDefaults(registry)
        assertNull(defaults.resolveDefaultFormat(null))
    }

    @Test
    fun `diagnostics reports CI mode correctly`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(makeReporter("console", "console", isDefaultInteractive = true))
            registry.register(makeReporter("markdown", "markdown", isDefaultCI = true))

            val defaults = SmartReporterDefaults(registry)
            val diag = defaults.diagnostics(ciDetection)

            assertTrue(diag.isCI)
            assertEquals("markdown", diag.selectedFormat)
            assertEquals("markdown", diag.ciDefault)
            assertEquals("console", diag.interactiveDefault)
            assertEquals(2, diag.availableFormats.size)
        }

    @Test
    fun `diagnostics reports interactive mode correctly`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(makeReporter("console", "console", isDefaultInteractive = true))

            val defaults = SmartReporterDefaults(registry)
            val diag = defaults.diagnostics(null)

            assertFalse(diag.isCI)
            assertEquals("console", diag.selectedFormat)
        }
}
