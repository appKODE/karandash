package io.karandash.cli.commands.validate

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.FormatModule
import io.karandash.api.module.OutputModule
import io.karandash.api.module.PlatformServiceModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.pipeline.CIContext
import io.karandash.cli.pipeline.ConfigLoadStage
import io.karandash.cli.pipeline.ReportGenerator
import io.karandash.cli.pipeline.ReportingStage
import io.karandash.cli.pipeline.ValidationOptions
import io.karandash.cli.pipeline.ValidationStage
import io.karandash.config.Config
import io.karandash.config.ReportConfig
import io.karandash.config.ReportTransparencyConfig
import io.karandash.config.TransparencyMode
import io.karandash.config.WalkthroughMode
import io.karandash.config.keys.ModuleIds
import io.karandash.engine.ValidationEngine
import io.karandash.engine.ValidationResult
import io.karandash.models.DiffRefs
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.config.configDeserializer
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the planner flags: an output module with an arbitrary ID (no `platform`,
 * `filesystem`, `slack` or `email` ID) gets the right treatment purely from its
 * [OutputModule] flags. Reverting the planner/generator to ID checks makes these fail.
 */
class OutputModuleFlagsTest {
    private class FakeFormat(
        override val formatId: String,
        override val isDefaultInteractive: Boolean = false,
        override val isDefaultPlatform: Boolean = false,
    ) : FormatModule {
        override val id = "fmt-$formatId"
        override val name = id
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun format(
            report: ValidationReport,
            config: JsonElement,
        ) = FormattedReport(formatId = formatId, body = "")
    }

    private class FakeOutput(
        override val outputId: String,
        override val preferredFormat: String? = null,
        override val supportsFileOutput: Boolean = false,
        override val requiresPlatformContext: Boolean = false,
        override val isDefaultInteractive: Boolean = false,
        override val isDefaultPlatform: Boolean = false,
        override val acceptedFormats: Set<String> = emptySet(),
    ) : OutputModule {
        override val id = "out-$outputId"
        override val name = id
        override val version = "1.0"
        var platformContextProject: String? = null

        override suspend fun initialize(context: PluginContext) = true

        override fun configurePlatformContext(
            platformService: PlatformServiceModule,
            projectId: String,
            mrId: String,
            diffRefs: DiffRefs?,
            diffLineRanges: Map<String, Set<Int>>,
        ) {
            platformContextProject = projectId
        }

        override suspend fun output(
            report: FormattedReport,
            config: JsonElement,
        ) = Unit
    }

    private class FakePlatform(
        override val id: String,
    ) : PlatformServiceModule {
        override val name = id
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String
        ) = error("unused")

        override suspend fun fetchMergeRequestByUrl(url: String) = error("unused")

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) = Unit

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) = Unit

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean
        ) = Unit

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String
        ) = Unit

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String
        ) = Unit

        override suspend fun approve(
            projectId: String,
            mrId: String
        ) = Unit

        override suspend fun unapprove(
            projectId: String,
            mrId: String
        ) = Unit

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String
        ) = ""

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String
        ) = Unit

        override suspend fun findExistingComment(
            projectId: String,
            mrId: String,
            marker: String
        ): String? = null

        override suspend fun postInlineComment(
            projectId: String,
            mrId: String,
            body: String,
            filePath: String,
            line: Int,
            diffRefs: DiffRefs,
            startLine: Int?,
        ) = ""
    }

    private fun planner(
        outputTarget: String? = null,
        hasPlatformContext: Boolean = false,
        format: String? = null,
        formatFlagOverrides: JsonObject = JsonObject(emptyMap()),
        logger: Logger = NoOpLogger,
    ) = FormatOutputPlanner(
        format = format,
        explicitFormat = format != null,
        outputTarget = outputTarget,
        hasPlatformContext = hasPlatformContext,
        logger = logger,
        formatFlagOverrides = formatFlagOverrides,
    )

    private fun reporters(name: String) = Config(reporters = mapOf(name to JsonObject(emptyMap())))

    @Test
    fun `preferredFormat picks the format for a reporters entry without format`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeFormat("json"))
            registry.register(FakeFormat("probe-fmt"))
            registry.register(FakeOutput("probe-out", preferredFormat = "probe-fmt"))

            val pairs = planner().selectPairs(reporters("probe-out"), registry)

            assertEquals(listOf("probe-fmt"), pairs.map { it.formatModule.formatId })
        }

    @Test
    fun `the reviewdog reporters key selects the rdjson format`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeFormat("json"))
            registry.register(FakeFormat("rdjson"))
            registry.register(FakeOutput("stdout", isDefaultInteractive = true))

            val pairs = planner().selectPairs(reporters("reviewdog"), registry)

            assertEquals(listOf("rdjson"), pairs.map { it.formatModule.formatId })
        }

    @Test
    fun `runPairs reports the measured format and output time per pair`() =
        runTest {
            val slowFormat = object : FormatModule {
                override val formatId = "slow"
                override val id = "fmt-slow"
                override val name = id
                override val version = "1.0"

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun format(
                    report: ValidationReport,
                    config: JsonElement,
                ): FormattedReport {
                    withContext(Dispatchers.Default) { delay(60) }
                    return FormattedReport(formatId = formatId, body = "")
                }
            }
            val slowOutput = object : OutputModule by FakeOutput("sink") {
                override suspend fun output(
                    report: FormattedReport,
                    config: JsonElement,
                ) {
                    withContext(Dispatchers.Default) { delay(60) }
                }
            }
            val pair = ResolvedFormatOutput(slowFormat, slowOutput, JsonObject(emptyMap()), JsonObject(emptyMap()))
            val timings = mutableListOf<Triple<String, Long, Long>>()

            planner().runPairs(listOf(pair), createTestReport(findings = emptyList())) { p, formatMs, outputMs ->
                timings += Triple(p.outputModule.outputId, formatMs, outputMs)
            }

            assertEquals("sink", timings.single().first)
            assertTrue(timings.single().second >= 50, timings.toString())
            assertTrue(timings.single().third >= 50, timings.toString())
        }

    @Test
    fun `no preferredFormat falls back to json`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeFormat("json"))
            registry.register(FakeFormat("probe-fmt"))
            registry.register(FakeOutput("probe-out"))

            val pairs = planner().selectPairs(reporters("probe-out"), registry)

            assertEquals(listOf("json"), pairs.map { it.formatModule.formatId })
        }

    @Test
    fun `supportsFileOutput output receives the output path`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeFormat("json", isDefaultInteractive = true))
            registry.register(FakeOutput("stdout", isDefaultInteractive = true))
            registry.register(FakeOutput("probe-file", supportsFileOutput = true))

            val pairs = planner(outputTarget = "report.txt").selectPairs(Config(), registry)

            assertEquals(listOf("probe-file"), pairs.map { it.outputModule.outputId })
            assertEquals(
                JsonObject(mapOf("outputFile" to JsonPrimitive("report.txt"))),
                pairs.single().outputConfig,
            )
        }

    @Test
    fun `reporters format entry with an output path writes that format to the file`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeFormat("json", isDefaultInteractive = true))
            registry.register(FakeFormat("junit"))
            registry.register(FakeOutput("stdout", isDefaultInteractive = true))
            registry.register(FakeOutput("probe-file", supportsFileOutput = true))
            val block = JsonObject(mapOf("output" to JsonPrimitive("build/junit.xml"), "includeAiMetadata" to JsonPrimitive(true)))

            val pairs = planner().selectPairs(Config(reporters = mapOf("junit" to block)), registry)

            val junit = pairs.single { it.formatModule.formatId == "junit" }
            assertEquals("probe-file", junit.outputModule.outputId)
            assertEquals(JsonPrimitive("build/junit.xml"), (junit.outputConfig as JsonObject)["outputFile"])
            assertEquals(JsonPrimitive(true), (junit.formatConfig as JsonObject)["includeAiMetadata"])
        }

    @Test
    fun `requiresPlatformContext output gets configurePlatformContext`() =
        runTest {
            val (output, stage) = generateWithPlatform("probeplat")

            assertEquals("proj-1", output.platformContextProject)
            assertEquals(listOf("probe-out"), stage.pairs.map { it.outputModule.outputId })
        }

    @Test
    fun `a local git run selects no platform output`() =
        runTest {
            val (output, stage) = generateWithPlatform(ModuleIds.LOCAL_GIT)

            assertEquals(emptyList(), stage.pairs.filter { it.outputModule.requiresPlatformContext })
            assertEquals(null, output.platformContextProject)
        }

    private suspend fun generateWithPlatform(platform: String): Pair<FakeOutput, ReportingStage> {
        val registry = ModuleRegistryImpl()
        registry.register(FakeFormat("json", isDefaultPlatform = true))
        val output = FakeOutput("probe-out", requiresPlatformContext = true, isDefaultPlatform = true)
        registry.register(output)
        registry.register(FakePlatform("probeplat"))
        val config = Config()
        val logger = NoOpLogger
        val bootstrap = PluginBootstrap()
        val configStage = ConfigLoadStage(
            config = config,
            configFilePath = null,
            usingZeroConfig = false,
            registry = registry,
            bootstrap = bootstrap,
        )
        val options = ValidationOptions(
            configPathStr = null,
            mrDataStr = null,
            mrId = null,
            platform = null,
            project = null,
            prUrl = null,
            format = "console",
            outputPathStr = null,
            quiet = false,
            verbose = false,
            strict = false,
            dryRun = false,
            showExecutionPlan = false,
            explain = false,
            fix = false,
            yes = false,
            noAutoDetect = false,
            outputWriter = {},
            sharedBootstrap = bootstrap,
            logger = logger,
        )
        val generator = ReportGenerator(
            logger = logger,
            options = options,
            runtimeModuleInitializer = { _, _, _ -> },
            resolveModuleConfig = { _, _, _, _ -> JsonObject(emptyMap()) },
            resolveCIContext = { CIContext(platform = platform, project = "proj-1", mrId = "7", isCI = true) },
        )

        val stage = generator.generateReports(
            configStage = configStage,
            scope = ValidateCommandScope(config, logger, existingRegistry = registry),
            validationStage = ValidationStage(ValidationEngine(config, registry), ValidationResult(emptyList())),
        )
        return output to stage
    }

    @Test
    fun `explicit format the platform output does not accept goes to the interactive output`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeFormat("sarif"))
            registry.register(FakeOutput("stdout", isDefaultInteractive = true))
            registry.register(FakeOutput("comment", isDefaultPlatform = true, acceptedFormats = setOf("markdown")))

            val pairs = planner(hasPlatformContext = true, format = "sarif").selectPairs(Config(), registry)

            assertEquals(listOf("sarif" to "stdout"), pairs.map { it.formatModule.formatId to it.outputModule.outputId })
        }

    @Test
    fun `reporters entry with a format its output does not accept is skipped`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeFormat("json"))
            registry.register(FakeOutput("chat", acceptedFormats = setOf("slack")))
            val config = Config(reporters = mapOf("chat" to JsonObject(mapOf("format" to JsonPrimitive("json")))))

            assertEquals(emptyList(), planner().selectPairs(config, registry).map { it.outputModule.outputId })
        }

    @Test
    fun `report keys reach the platform format and only a changed key is injected`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeFormat("console", isDefaultInteractive = true))
            registry.register(FakeFormat("markdown", isDefaultPlatform = true))
            registry.register(FakeOutput("stdout", isDefaultInteractive = true))
            registry.register(FakeOutput("platform", requiresPlatformContext = true, isDefaultPlatform = true))
            val report =
                ReportConfig(
                    transparency = ReportTransparencyConfig(summary = TransparencyMode.OFF, inlineWhy = true),
                    walkthrough = WalkthroughMode.COLLAPSED
                )

            val changed = planner(hasPlatformContext = true).selectPairs(Config(report = report), registry)
            val defaults = planner(hasPlatformContext = true).selectPairs(Config(), registry)

            val markdown = changed.single { it.formatModule.formatId == "markdown" }.formatConfig as JsonObject
            val transparency = markdown["transparency"] as JsonObject
            assertEquals(JsonPrimitive("off"), transparency["summary"])
            assertEquals(JsonPrimitive(true), transparency["inlineWhy"])
            assertEquals(JsonPrimitive("collapsed"), markdown["walkthrough"])
            val local = planner().selectPairs(Config(report = report), registry)
            assertEquals(JsonObject(emptyMap()), local.single { it.formatModule.formatId == "console" }.formatConfig)
            assertTrue(defaults.all { it.formatConfig == JsonObject(emptyMap()) }, "$defaults")
        }

    @Test
    fun `CLI flag overrides are laid over the configured format config`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeFormat("console", isDefaultInteractive = true))
            registry.register(FakeOutput("stdout", isDefaultInteractive = true))
            val config = Config(reporters = mapOf("console" to JsonObject(mapOf("verbose" to JsonPrimitive(false)))))
            val flags = JsonObject(mapOf("verbose" to JsonPrimitive(true), "quiet" to JsonPrimitive(true)))

            val pairs = planner(formatFlagOverrides = flags).selectPairs(config, registry)

            assertEquals(flags, pairs.first().formatConfig)
        }

    @Test
    fun `explicit platform-context output without platform context goes to the interactive output`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeFormat("json", isDefaultInteractive = true, isDefaultPlatform = true))
            registry.register(FakeOutput("stdout", isDefaultInteractive = true))
            registry.register(FakeOutput("comment", requiresPlatformContext = true))

            val local = planner(outputTarget = "comment").selectPairs(Config(), registry)
            val live = planner(outputTarget = "comment", hasPlatformContext = true).selectPairs(Config(), registry)

            assertEquals(listOf("stdout"), local.map { it.outputModule.outputId })
            assertEquals(listOf("comment"), live.map { it.outputModule.outputId })
        }

    @Test
    fun `file path with no file-capable output warns and keeps the default output without outputFile`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeFormat("json", isDefaultInteractive = true))
            registry.register(FakeOutput("stdout", isDefaultInteractive = true))
            val warnings = mutableListOf<String>()
            val logger = object : Logger {
                override fun debug(message: String) = Unit

                override fun info(message: String) = Unit

                override fun warn(message: String) {
                    warnings += message
                }

                override fun error(message: String) = Unit
            }

            val pair = planner(outputTarget = "report.txt", logger = logger).selectPairs(Config(), registry).single()

            assertEquals("stdout", pair.outputModule.outputId)
            assertTrue("outputFile" !in pair.outputConfig.toString(), pair.outputConfig.toString())
            assertTrue(warnings.single().contains("no output module supports file output"), warnings.toString())
        }

    @Test
    fun `report language reaches every format and output config without overriding a set language`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeFormat("console", isDefaultInteractive = true))
            registry.register(FakeFormat("json"))
            registry.register(FakeOutput("stdout", isDefaultInteractive = true))
            registry.register(FakeOutput("probe-out", preferredFormat = "json"))
            val config = Config(
                report = ReportConfig("ru"),
                reporters = mapOf("probe-out" to JsonObject(mapOf("language" to JsonPrimitive("pl")))),
            )

            val pairs = planner().selectPairs(config, registry).associateBy { it.outputModule.outputId }

            assertEquals(JsonPrimitive("ru"), (pairs.getValue("stdout").formatConfig as JsonObject)["language"])
            assertEquals(JsonPrimitive("ru"), (pairs.getValue("stdout").outputConfig as JsonObject)["language"])
            assertEquals(JsonPrimitive("pl"), (pairs.getValue("probe-out").outputConfig as JsonObject)["language"])
        }

    @Test
    fun `the default English report language adds no language key`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeFormat("console", isDefaultInteractive = true))
            registry.register(FakeOutput("stdout", isDefaultInteractive = true))

            val pair = planner().selectPairs(Config(), registry).single()

            assertEquals(JsonObject(emptyMap()), pair.formatConfig)
            assertEquals(JsonObject(emptyMap()), pair.outputConfig)
        }

    @Test
    fun `an injected language key still decodes into a plugin typed config that does not declare it`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeFormat("console", isDefaultInteractive = true))
            registry.register(FakeOutput("stdout", isDefaultInteractive = true))
            val config =
                Config(report = ReportConfig("ru"), reporters = mapOf("stdout" to JsonObject(mapOf("title" to JsonPrimitive("t")))))

            val outputConfig = planner().selectPairs(config, registry).single().outputConfig

            assertEquals(JsonPrimitive("ru"), (outputConfig as JsonObject)["language"])
            assertEquals(
                PluginReporterConfig("t"),
                configDeserializer.decodeFromJsonElement(PluginReporterConfig.serializer(), outputConfig)
            )
        }

    /** A third-party reporter's typed config, decoded the SDK way ([configDeserializer]), with no `language` field. */
    @Serializable
    private data class PluginReporterConfig(
        val title: String = "",
    )
}
