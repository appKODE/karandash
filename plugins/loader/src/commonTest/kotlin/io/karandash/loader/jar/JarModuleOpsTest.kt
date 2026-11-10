package io.karandash.loader.jar

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.config.ConfigProperty
import io.karandash.api.config.ConfigPropertyType
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.CliCommandModule
import io.karandash.api.module.FormatModule
import io.karandash.api.module.OutputModule
import io.karandash.api.module.PipelineEnvironment
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.ScriptModule
import io.karandash.loader.bridge.BridgeCliResult
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The module, content, pipeline and command JAR bridge ops decode sparse host arguments and encode each result. */
class JarModuleOpsTest {
    private abstract class Fake(
        override val id: String,
    ) : io.karandash.api.Module {
        override val name: String get() = id
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true
    }

    private class Format :
        Fake("fake-format"),
        FormatModule {
        override val formatId = "fake"

        override suspend fun format(
            report: ValidationReport,
            config: JsonElement,
        ) = FormattedReport(formatId, "${report.findings.size} findings")
    }

    private class Output(
        private val failure: Throwable?,
    ) : Fake("fake-output"),
        OutputModule {
        override val outputId = "sink"

        override suspend fun output(
            report: FormattedReport,
            config: JsonElement,
        ) {
            failure?.let { throw it }
        }
    }

    private class Script :
        Fake("fake-script"),
        ScriptModule {
        val seen = mutableListOf<String>()

        override suspend fun execute(
            scripts: List<JsonElement>,
            mr: MergeRequest,
            context: PluginContext,
        ): List<Finding> {
            seen += "${scripts.size}"
            return emptyList()
        }
    }

    private class Extension :
        Fake("fake-extension"),
        PipelineExtension {
        override val type = ModuleType.PIPELINE_EXTENSION
        val environments = mutableListOf<PipelineEnvironment>()

        override suspend fun onConfigured(env: PipelineEnvironment) {
            environments += env
        }
    }

    private class Commands :
        Fake("fake-commands"),
        CliCommandModule {
        override suspend fun cliCommands() =
            listOf(
                PluginCommandSpec(
                    "example",
                    "group",
                    subcommands = listOf(
                        PluginCommandSpec("greet", "says hi") { ctx ->
                            ctx.echo("hi ${ctx.rawArgs.joinToString(" ")}")
                            ctx.echoError("done")
                            3
                        },
                    ),
                ),
            )

        override suspend fun configSchema() =
            listOf(
                ConfigProperty("plain", ConfigPropertyType.STRING, "no extras"),
                ConfigProperty("full", ConfigPropertyType.STRING, "all extras", true, "a", listOf("a", "b"), "use plain"),
            )
    }

    private suspend fun io.karandash.api.Module.invoke(
        op: String,
        vararg args: String,
    ): String? = JarBridgeDispatcher.invoke(this, op, JarInvocation(args.toList()) { TestPluginContext() })

    private val report get() = KarandashJson.Report.encodeToString(ValidationReport.serializer(), createTestReport())

    private val mr get() = KarandashJson.Default.encodeToString(MergeRequest.serializer(), createTestMergeRequest())

    @Test
    fun `a format module answers formatId and formats the decoded report`() =
        runTest {
            val format = Format()
            assertEquals("fake", format.invoke("formatId"))
            val formatted = KarandashJson.Default.decodeFromString(
                FormattedReport.serializer(),
                checkNotNull(format.invoke("format", report, "{}"))
            )
            assertEquals("2 findings", formatted.body)
        }

    @Test
    fun `output answers null on success and the failure message otherwise`() =
        runTest {
            val formatted = KarandashJson.Default.encodeToString(FormattedReport.serializer(), FormattedReport("fake", "body"))
            assertEquals("sink", Output(null).invoke("outputId"))
            assertNull(Output(null).invoke("output", formatted, "{}"))
            assertEquals("disk full", Output(IllegalStateException("disk full")).invoke("output", formatted, "{}"))
            assertEquals("output failed", Output(IllegalStateException()).invoke("output", formatted, "{}"))
        }

    @Test
    fun `execute treats a non-array scripts value as no scripts`() =
        runTest {
            val script = Script()
            assertEquals("[]", script.invoke("execute", """[{"a":1},{"b":2}]""", mr, "ctx"))
            assertEquals("[]", script.invoke("execute", """{"a":1}""", mr, ""))
            assertEquals(listOf("2", "0"), script.seen)
        }

    @Test
    fun `onConfigured reads present and absent environment keys`() =
        runTest {
            val extension = Extension()
            assertNull(extension.invoke("onConfigured", """{"rawConfig":{"k":1},"statsDir":"/s","platformId":"gitlab"}"""))
            assertNull(extension.invoke("onConfigured", "{}"))
            val (full, empty) = extension.environments
            assertEquals(
                "1",
                full.rawConfig
                    ?.jsonObject
                    ?.get("k")
                    ?.jsonPrimitive
                    ?.content
            )
            assertEquals("/s", full.statsDir)
            assertEquals("gitlab", full.platformId)
            assertNull(empty.rawConfig)
            assertEquals("", empty.statsDir)
            assertNull(empty.platformId)
        }

    @Test
    fun `runCliCommand follows the subcommand path and captures echo`() =
        runTest {
            val commands = Commands()
            val listed = KarandashJson.Default.parseToJsonElement(checkNotNull(commands.invoke("cliCommands"))).jsonArray
            assertEquals(
                "greet",
                listed[0]
                    .jsonObject["subcommands"]
                    ?.jsonArray
                    ?.get(0)
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content
            )
            val run = { raw: String? -> KarandashJson.Default.decodeFromString(BridgeCliResult.serializer(), checkNotNull(raw)) }
            assertEquals(
                BridgeCliResult(3, listOf("hi a b"), listOf("done")),
                run(commands.invoke("runCliCommand", "example  greet", """["a","b"]"""))
            )
            assertEquals(listOf("hi "), run(commands.invoke("runCliCommand", "example greet", "")).stdout)
            assertFailsWith<IllegalArgumentException> { commands.invoke("runCliCommand", "example", "") }
            assertFailsWith<IllegalArgumentException> { commands.invoke("runCliCommand", "missing greet", "") }
        }

    @Test
    fun `configSchema writes optional fields only when set`() =
        runTest {
            val schema = KarandashJson.Default.parseToJsonElement(checkNotNull(Commands().invoke("configSchema"))).jsonArray
            val plain = schema[0].jsonObject
            assertFalse("defaultValue" in plain || "enumValues" in plain || "deprecated" in plain)
            val full = schema[1].jsonObject
            assertEquals(JsonPrimitive("a"), full["defaultValue"])
            assertEquals(2, full["enumValues"]?.jsonArray?.size)
            assertEquals("use plain", full["deprecated"]?.jsonPrimitive?.content)
            assertTrue(full["required"]?.jsonPrimitive?.content == "true")
        }
}
