package io.karandash.sdk

import io.karandash.api.support.mapToJsonElement
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.sdk.typed.AbstractTypedReporterModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypedReporterModuleTest {
    @Serializable
    data class StubReporterConfig(
        val format: String = "text",
        val verbose: Boolean = false,
    )

    private class StubReporter : AbstractTypedReporterModule<StubReporterConfig>(StubReporterConfig.serializer()) {
        override val id = "io.karandash.test.stub-reporter"
        override val name = "Stub Reporter"
        override val version = "0.0.1"
        override val formatId = "stub"

        var lastConfig: StubReporterConfig? = null
        var lastFindings: List<Finding>? = null
        var lastPassed: Boolean? = null

        override suspend fun reportTyped(
            findings: List<Finding>,
            passed: Boolean,
            config: StubReporterConfig
        ): String? {
            lastConfig = config
            lastFindings = findings
            lastPassed = passed
            return if (config.verbose) "verbose-output" else null
        }
    }

    @Test
    fun `report bridge delegates to reportTyped with deserialized config`() =
        runTest {
            val reporter = StubReporter()
            val findings = listOf(Finding(severity = Severity.WARNING, message = "test", rule = "r1"))
            reporter.report(findings, passed = true, config = mapToJsonElement(mapOf("format" to "json", "verbose" to true)))

            assertEquals(StubReporterConfig(format = "json", verbose = true), reporter.lastConfig)
            assertEquals(findings, reporter.lastFindings)
            assertEquals(true, reporter.lastPassed)
        }

    @Test
    fun `report bridge uses defaults for empty config map`() =
        runTest {
            val reporter = StubReporter()
            reporter.report(emptyList(), passed = false, config = kotlinx.serialization.json.JsonObject(emptyMap()))

            assertEquals(StubReporterConfig(format = "text", verbose = false), reporter.lastConfig)
            assertEquals(false, reporter.lastPassed)
        }

    @Test
    fun `report returns value from reportTyped`() =
        runTest {
            val reporter = StubReporter()
            val result = reporter.report(emptyList(), passed = true, config = mapToJsonElement(mapOf("verbose" to true)))
            assertEquals("verbose-output", result)
        }

    @Test
    fun `report returns null when reportTyped returns null`() =
        runTest {
            val reporter = StubReporter()
            val result = reporter.report(emptyList(), passed = true, config = mapToJsonElement(mapOf("verbose" to false)))
            assertNull(result)
        }

    @Test
    fun `configSchema delegates to serializer schema`() {
        runTest {
            val reporter = StubReporter()
            val schema = reporter.configSchema()
            assertFalse(schema.isEmpty())
            assertEquals(2, schema.size)
            assertEquals("format", schema[0].name)
            assertEquals("verbose", schema[1].name)
        }
    }

    @Test
    fun `initialize returns true by default`() =
        runTest {
            val reporter = StubReporter()
            val context = object : PluginContext {
                override val pluginConfig: JsonElement = kotlinx.serialization.json.JsonObject(emptyMap())
                override val hostVersion: String = "0.0.0"
                override val workingDirectory: String = "."

                override fun env(name: String): String? = null

                override fun log(message: String) {}

                override fun warn(message: String) {}
            }
            assertTrue(reporter.initialize(context))
        }

    @Test
    fun `type returns REPORTER`() {
        val reporter = StubReporter()
        assertEquals(ModuleType.REPORTER, reporter.type)
    }
}
