package io.karandash.cli.pipeline

import io.karandash.api.finding.FormattedReport
import io.karandash.api.module.OutputModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.sdk.typed.TypedOutputModule
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every output destination carries the shared `onError: fail | warn` knob: under `warn` a failed delivery is
 * logged and the output goes on, under `fail` it throws, and the default keeps each destination's old behaviour.
 * A new output module fails [every output is declared] until it is added here.
 */
class OutputDestinationContractTest {
    private val report = FormattedReport(formatId = "json", body = "{}")

    /** A config that fails delivery without the network, and whether a failure fails the output by default. */
    private class Destination(
        val failing: Map<String, Any>,
        val failsByDefault: Boolean,
    )

    private val blockedFile: String by lazy {
        val dir = TestFiles.createTestTempDirectory("karandash-output-contract")
        val file = Path(dir, "a-file")
        TestFiles.writeText(file, "x")
        Path(file, "sub", "report.txt").toString()
    }

    private val destinations: Map<String, Destination> by lazy {
        mapOf(
            "webhook" to Destination(mapOf("url" to ""), failsByDefault = true),
            "filesystem" to Destination(mapOf("outputFile" to blockedFile), failsByDefault = true),
            "slack" to Destination(mapOf("webhook" to ""), failsByDefault = false),
            "email" to Destination(
                mapOf("host" to "smtp.example.com", "to" to "a@example.com", "from" to "b@example.com", "starttls" to false),
                failsByDefault = false,
            ),
        )
    }

    /** Destinations whose failing delivery needs fakes this test does not have; tested in their own module. */
    private val testedInModule = mapOf(
        "platform" to "PlatformOutputReconcileTest onError fail",
        "status" to "StatusOutputModuleTest a failed publish warns by default and fails the output under onError fail",
    )

    /** Outputs with no `onError` and why. */
    private val notApplicable = mapOf("stdout" to "printing to stdout has no delivery failure to handle")

    private suspend fun outputs(): List<OutputModule> {
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        return bootstrap.registry().outputs()
    }

    private fun json(config: Map<String, Any>): JsonObject =
        JsonObject(config.mapValues { (_, v) -> if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) })

    @Test
    fun `every output is declared`() =
        runTest {
            val ids = outputs().map { it.outputId }.toSet()
            assertEquals(destinations.keys + testedInModule.keys + notApplicable.keys, ids)
        }

    @Test
    fun `every destination config has onError`() =
        runTest {
            outputs().filter { it.outputId !in notApplicable }.forEach { output ->
                val descriptor = (output as TypedOutputModule<*>).configSerializer.descriptor
                val names = List(descriptor.elementsCount, descriptor::getElementName)
                assertTrue("onError" in names, "${output.outputId} config has no onError: $names")
            }
        }

    @Test
    fun `onError warn logs a failed delivery and onError fail throws it`() =
        runTest {
            val byId = outputs().associateBy { it.outputId }
            destinations.forEach { (id, destination) ->
                val output = checkNotNull(byId[id]) { "no output $id" }

                val warned = TestPluginContext()
                output.initialize(warned)
                output.output(report, json(destination.failing + ("onError" to "warn")))
                assertEquals(1, warned.warnings.size, "$id warnings: ${warned.warnings}")

                output.initialize(TestPluginContext())
                assertFailsWith<Exception>(id) { output.output(report, json(destination.failing + ("onError" to "fail"))) }

                output.initialize(TestPluginContext())
                if (destination.failsByDefault) {
                    assertFailsWith<Exception>("$id default") { output.output(report, json(destination.failing)) }
                } else {
                    output.output(report, json(destination.failing))
                }
                output.shutdown()
            }
        }
}
