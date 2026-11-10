@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.commands.validate

import io.karandash.api.Module
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.finding.AnalyzerProvenance
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.FormatModule
import io.karandash.api.module.OutputModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.pipeline.CIContext
import io.karandash.cli.pipeline.ConfigLoadStage
import io.karandash.cli.pipeline.ReportGenerator
import io.karandash.cli.pipeline.ValidationOptions
import io.karandash.cli.pipeline.ValidationStage
import io.karandash.config.Config
import io.karandash.config.DryRunScope
import io.karandash.config.keys.ModuleIds
import io.karandash.engine.ValidationEngine
import io.karandash.engine.ValidationResult
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `dry-run-scope: all` skips outputs with external side effects; anything else runs every output. */
class DryRunScopeOutputTest {
    private class JsonFormat : FormatModule {
        override val formatId = "json"
        override val id = "fmt-json"
        override val name = id
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun format(
            report: ValidationReport,
            config: JsonElement,
        ) = FormattedReport(formatId = formatId, body = "")
    }

    /** Records the config the pipeline hands the markdown format. */
    private class MarkdownFormat(
        private val configs: MutableList<JsonElement>,
    ) : FormatModule {
        override val formatId = "markdown"
        override val id = "fmt-markdown"
        override val name = id
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun format(
            report: ValidationReport,
            config: JsonElement,
        ): FormattedReport {
            configs += config
            return FormattedReport(formatId = formatId, body = "")
        }
    }

    /** Leaves [hasExternalSideEffects] at the SDK default unless [local]. */
    private class RecordingOutput(
        override val outputId: String,
        private val written: MutableList<String>,
        private val local: Boolean = false,
    ) : OutputModule {
        override val id = "out-$outputId"
        override val name = id
        override val version = "1.0"
        override val hasExternalSideEffects: Boolean get() = if (local) false else super.hasExternalSideEffects

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun output(
            report: FormattedReport,
            config: JsonElement,
        ) {
            written += outputId
        }
    }

    private suspend fun outputsRun(
        dryRun: Boolean,
        scope: DryRunScope?,
        markdownConfigs: MutableList<JsonElement> = mutableListOf(),
        markdown: FormatModule = MarkdownFormat(markdownConfigs),
        result: ValidationResult = ValidationResult(emptyList()),
        initializer: suspend (Module) -> Unit = {},
    ): List<String> {
        val written = mutableListOf<String>()
        val registry = ModuleRegistryImpl()
        registry.register(JsonFormat())
        registry.register(markdown)
        registry.register(RecordingOutput("third-party", written))
        registry.register(RecordingOutput("local", written, local = true))
        val config = Config(
            reporters = mapOf(
                "third-party" to JsonObject(emptyMap()),
                "local" to JsonObject(mapOf("format" to JsonPrimitive("markdown"))),
            ),
            dryRun = dryRun,
            dryRunScope = scope,
        )
        val bootstrap = PluginBootstrap()
        val options = ValidationOptions(
            configPathStr = null,
            mrDataStr = null,
            mrId = null,
            platform = null,
            project = null,
            prUrl = null,
            format = "json",
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
            logger = NoOpLogger,
        )
        ReportGenerator(
            logger = NoOpLogger,
            options = options,
            runtimeModuleInitializer = { _, module, _ -> initializer(module) },
            resolveModuleConfig = { _, _, _, _ -> JsonObject(emptyMap()) },
            resolveCIContext = { CIContext(platform = null, project = null, mrId = null, isCI = false) },
        ).generateReports(
            configStage = ConfigLoadStage(
                config = config,
                configFilePath = null,
                usingZeroConfig = false,
                registry = registry,
                bootstrap = bootstrap,
            ),
            scope = ValidateCommandScope(config, NoOpLogger, existingRegistry = registry),
            validationStage = ValidationStage(ValidationEngine(config, registry), result),
        )
        return written.sorted()
    }

    @Test
    fun `scope all skips side-effecting outputs - third-party ones by default`() =
        runTest {
            assertEquals(listOf("local"), outputsRun(dryRun = true, scope = DryRunScope.ALL))
        }

    @Test
    fun `scope ai - no scope - or dry-run off runs every output`() =
        runTest {
            val all = listOf("local", "third-party")
            assertEquals(all, outputsRun(dryRun = true, scope = DryRunScope.AI))
            assertEquals(all, outputsRun(dryRun = true, scope = null))
            assertEquals(all, outputsRun(dryRun = false, scope = DryRunScope.ALL))
        }

    @Test
    fun `a dry run under scope all writes no review flow artifact`() =
        runTest {
            val dry = mutableListOf<JsonElement>()
            outputsRun(dryRun = true, scope = DryRunScope.ALL, markdownConfigs = dry)
            assertEquals(JsonPrimitive(""), (dry.single() as JsonObject)[ModuleIds.FLOW_ARTIFACT_DIR_KEY])

            val live = mutableListOf<JsonElement>()
            outputsRun(dryRun = false, scope = DryRunScope.ALL, markdownConfigs = live)
            assertFalse("flowArtifactDir" in (live.single() as JsonObject))
        }

    private fun contextIn(dir: Path): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = JsonObject(emptyMap())
            override val logger: Logger = NoOpLogger
            override val hostVersion: String = "test"
            override val workingDirectory: String = dir.toString()

            override fun env(name: String): String? = null
        }

    /** Runs the real markdown format with a provenance-carrying result in [dir]; true when `review-flow.md` was written. */
    private suspend fun flowArtifactWritten(
        dir: Path,
        dryRun: Boolean,
    ): Boolean {
        val real = PluginBootstrap()
            .also { it.loadPlugins() }
            .registry()
            .formats()
            .first { it.formatId == ModuleIds.MARKDOWN_FORMAT }
        val provenance = ReviewProvenance(analyzers = listOf(AnalyzerProvenance("aiReview", model = "m", findings = 1)))
        outputsRun(
            dryRun = dryRun,
            scope = DryRunScope.ALL,
            markdown = real,
            result = ValidationResult(emptyList(), provenance = provenance),
            initializer = { if (it === real) real.initialize(contextIn(dir)) },
        )
        return TestFiles.exists(Path(dir, ".karandash/debug/review-flow.md"))
    }

    @Test
    fun `the real markdown format writes the flow artifact on a live run but not on a dry run`() =
        runTest {
            val live = TestFiles.createTestTempDirectory("karandash-brk39-live")
            val dry = TestFiles.createTestTempDirectory("karandash-brk39-dry")
            assertTrue(flowArtifactWritten(live, dryRun = false), "a live run must write review-flow.md")
            assertFalse(flowArtifactWritten(dry, dryRun = true), "a dry run must not write review-flow.md")
        }
}
