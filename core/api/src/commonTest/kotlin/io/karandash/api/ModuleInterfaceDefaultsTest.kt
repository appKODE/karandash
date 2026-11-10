package io.karandash.api

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.AiInterceptChain
import io.karandash.api.ai.AiInterceptContext
import io.karandash.api.ai.AiInterceptorModule
import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.AiPromptProcessorContext
import io.karandash.api.ai.AiPromptProcessorModule
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.ai.AiRedactorContext
import io.karandash.api.ai.AiRedactorModule
import io.karandash.api.ai.AiReportModule
import io.karandash.api.ai.AiResponseTransformerContext
import io.karandash.api.ai.AiResponseTransformerModule
import io.karandash.api.ai.AiTokenAllocatorModule
import io.karandash.api.ai.BlockBudget
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.CliCommandModule
import io.karandash.api.module.ConfigFormatModule
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.api.module.FormatModule
import io.karandash.api.module.ModuleScope
import io.karandash.api.module.OutputModule
import io.karandash.api.module.PipelineEnvironment
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.ProfileModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.api.module.SchemaAwareFormatModule
import io.karandash.api.module.ScriptModule
import io.karandash.api.support.shouldInitializeDuringBootstrap
import io.karandash.logger.NoOpLogger
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Every documented default of the module interfaces, observed through minimal implementations. */
@OptIn(ExperimentalKarandashApi::class, InternalKarandashApi::class)
class ModuleInterfaceDefaultsTest {
    private abstract class Stub(
        override val id: String = "stub",
    ) : Module {
        override val name = "Stub"
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true
    }

    private val mr = MergeRequest(
        id = "1",
        iid = 1,
        title = "t",
        description = null,
        sourceBranch = "f",
        targetBranch = "main",
        author = User("dev", "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = emptyList(),
    )
    private val finding = Finding(Severity.ERROR, "m", "r")
    private val response = AiCompletionResponse("c", 1, 2, 3, "model")
    private val request = AiCompletionRequest(prompt = "p", model = "model")

    private class Provider(
        override val apiKeyEnvVar: String? = null,
    ) : Stub("provider"),
        AiProviderModule {
        override val providerId = "p"

        override suspend fun complete(request: AiCompletionRequest) = AiCompletionResponse(request.prompt, 0, 0, 0, request.model)
    }

    private class Analyzer :
        Stub("analyzer"),
        AiAnalyzerModule {
        override val analyzerId = "a"

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> = context.skip("nothing")
    }

    private class Metric :
        Stub("metric"),
        AiMetricModule {
        override val metricId = "m"
        override val metricName = "M"
        override val unit = "count"

        override suspend fun compute(context: ReportContext) = MetricResult(metricId, context.findings.size.toDouble(), unit)
    }

    private class Report :
        Stub("report"),
        AiReportModule {
        override val reportId = "r"
        override val reportName = "R"

        override suspend fun generate(context: ReportContext) = ReportResult(reportId, JsonNull, "")
    }

    private class Rule :
        Stub("rule"),
        RuleModule {
        override suspend fun contributedRules() = setOf("r")

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ) = listOf(Finding(Severity.INFO, ruleId, ruleId))
    }

    private class Reporter :
        Stub("reporter"),
        ReporterModule {
        override val formatId = "fmt"

        override suspend fun report(
            findings: List<Finding>,
            passed: Boolean,
            config: JsonElement,
        ): String? = null
    }

    private class Output :
        Stub("output"),
        OutputModule {
        override val outputId = "out"

        override suspend fun output(
            report: FormattedReport,
            config: JsonElement,
        ) = Unit
    }

    private class Format :
        Stub("format"),
        FormatModule {
        override val formatId = "fmt"

        override suspend fun format(
            report: ValidationReport,
            config: JsonElement,
        ) = FormattedReport(formatId, "")
    }

    private class Extension :
        Stub("ext"),
        PipelineExtension {
        override val type = ModuleType.PIPELINE_EXTENSION
    }

    private class Enrichment :
        Stub("enrich"),
        EnrichmentProviderModule {
        override suspend fun enrich(
            mr: MergeRequest,
            config: JsonElement,
            upstreamContext: EnrichmentContext,
        ): Map<String, JsonElement> = emptyMap()
    }

    private class ConfigFormat(
        private val parsed: JsonElement,
    ) : SchemaAwareFormatModule {
        override val formatId = "cfg"

        override suspend fun parseToJsonElement(content: String) = parsed
    }

    @Test
    fun `ai provider defaults and api key requirement`() =
        runTest {
            val plain = Provider()
            assertEquals(ModuleType.AI_PROVIDER, plain.type)
            assertEquals("", plain.defaultModel)
            assertNull(plain.baseUrlEnvVar)
            assertFalse(plain.requiresApiKey)
            assertFalse(plain.supportsContextBlocks)
            assertTrue(Provider(apiKeyEnvVar = "KEY").requiresApiKey)
            assertEquals("p", plain.complete(request).content)
        }

    @Test
    fun `ai analyzer defaults and skip notifies the callback`() =
        runTest {
            val analyzer = Analyzer()
            assertEquals(ModuleType.AI_ANALYZER, analyzer.type)
            assertEquals(0, analyzer.estimatedTokens)
            assertEquals("", analyzer.explanation)
            assertEquals("a", analyzer.featureKey)
            assertFalse(analyzer.defaultEnabled)
            assertNull(analyzer.defaultPrompt)
            assertFalse(analyzer.needsScopeDetection)
            assertFalse(analyzer.needsBlame)
            assertFalse(analyzer.mrLevel)
            assertNull(analyzer.responseFormatSuffix)
            assertFalse(analyzer.supportsSemiFormalReasoning)
            assertNull(analyzer.resultSerializer)
            assertEquals(emptyList(), analyzer.supportedEnrichment)
            assertEquals(emptyList(), analyzer.requiredEnrichment)

            val skipped = mutableListOf<String>()
            val context = analyzerContext(onSkip = { skipped += it })
            assertEquals(emptyList(), analyzer.analyze(context))
            assertEquals(listOf("nothing"), skipped)
        }

    private fun analyzerContext(onSkip: (String) -> Unit) =
        AiAnalyzerContext(
            mr = mr,
            aiService = object : io.karandash.api.ai.AiService {
                override val isAvailable = false

                override suspend fun complete(
                    request: AiCompletionRequest,
                    providerId: String?,
                ) = response
            },
            budget = object : io.karandash.api.ai.AiTokenBudget {
                override fun canSpend(estimatedTokens: Int) = true

                override fun recordSpend(tokens: Int) = Unit

                override fun remaining() = 0

                override fun isExhausted() = false
            },
            model = "model",
            logger = NoOpLogger,
            onSkip = onSkip,
        )

    @Test
    fun `ai pipeline stage modules default to priority 100 and pass through`() =
        runTest {
            val enricher = object : Stub(), AiContextEnricherModule {
                override suspend fun enrich(
                    mr: MergeRequest,
                    context: AiContextEnricherContext,
                ) = context.existingContext
            }
            val processor = object : Stub(), AiPromptProcessorModule {
                override suspend fun processPrompt(
                    prompt: String,
                    context: AiPromptProcessorContext,
                ) = prompt
            }
            val interceptor = object : Stub(), AiInterceptorModule {
                override suspend fun intercept(
                    request: AiCompletionRequest,
                    context: AiInterceptContext,
                    chain: AiInterceptChain,
                ) = chain.proceed(request)
            }
            val transformer = object : Stub(), AiResponseTransformerModule {
                override suspend fun transform(
                    response: AiCompletionResponse,
                    request: AiCompletionRequest,
                    context: AiResponseTransformerContext,
                ) = response
            }
            val redactor = object : Stub(), AiRedactorModule {
                override suspend fun redact(
                    text: String,
                    context: AiRedactorContext,
                ) = text
            }
            val allocator = object : Stub(), AiTokenAllocatorModule {
                override suspend fun allocate(
                    rules: Set<String>,
                    totalBudget: Int,
                    priorities: Map<String, Int>,
                ) = rules.associateWith { totalBudget }
            }

            assertEquals(
                listOf(100, 100, 100, 100, 100),
                listOf(
                    enricher.enricherPriority,
                    processor.processorPriority,
                    interceptor.interceptorPriority,
                    transformer.transformerPriority,
                    redactor.redactorPriority,
                ),
            )
            assertEquals(emptyList(), enricher.handledEnrichmentIds())
            assertNull(enricher.provideBlock(mr, emptyList()))
            assertEquals(mapOf("k" to "v"), enricher.enrich(mr, AiContextEnricherContext(null, mapOf("k" to "v"))))
            assertEquals("sys", processor.processSystemPrompt("sys", AiPromptProcessorContext(null, "m", null)))
            assertNull(processor.processSystemPrompt(null, AiPromptProcessorContext(null, "m", null)))
            assertSame(response, interceptor.intercept(request, AiInterceptContext(null, null, null)) { response })
            assertSame(response, transformer.transform(response, request, AiResponseTransformerContext(null, null)))
            assertEquals("x", redactor.redact("x", AiRedactorContext(null, null)))
            assertEquals(mapOf("r" to 5), allocator.allocate(setOf("r"), 5, emptyMap()))
            assertEquals(
                listOf(
                    ModuleType.AI_CONTEXT_ENRICHER,
                    ModuleType.AI_PROMPT_PROCESSOR,
                    ModuleType.AI_INTERCEPTOR,
                    ModuleType.AI_RESPONSE_TRANSFORMER,
                    ModuleType.AI_REDACTOR,
                    ModuleType.AI_TOKEN_ALLOCATOR,
                ),
                listOf(enricher, processor, interceptor, transformer, redactor, allocator).map { it.type },
            )
        }

    @Test
    fun `ai report and metric require findings by default`() =
        runTest {
            val context = ReportContext(listOf(finding), mr, EnrichmentContext.EMPTY, JsonNull)
            assertEquals(setOf(ReportInput.FINDINGS), Report().requiredInputs)
            assertEquals(setOf(ReportInput.FINDINGS), Metric().requiredInputs)
            assertEquals(ModuleType.AI_REPORT, Report().type)
            assertEquals(ModuleType.AI_METRIC, Metric().type)
            assertEquals(1.0, Metric().compute(context).value)
            assertEquals("r", Report().generate(context).reportId)
            assertEquals(emptyList(), context.history)
        }

    @Test
    fun `module base defaults derive from id and name`() =
        runTest {
            val module = object : Stub("my-module") {
                override val type = ModuleType.RULE
            }
            assertEquals("Stub", module.description)
            assertEquals("my-module", module.dslName)
            assertEquals("my-module", module.configKey)
            assertEquals(emptyList(), module.messages())
            assertEquals(emptyList(), module.configSchema())
            assertNull(module.hoverDocumentation)
            assertEquals(emptySet(), module.preserveKeys())
            assertEquals(ModuleScope.SHARED, module.scope)
            assertSame(module, module.newHandle(analyzerContextPlugin))
            assertEquals(KarandashApi.MIN_SUPPORTED_LEVEL, module.minApiLevel)
            module.shutdown()
            assertFalse(module.shouldInitializeDuringBootstrap())
        }

    private val analyzerContextPlugin = object : PluginContext {
        override val pluginConfig: JsonElement = JsonObject(emptyMap())
        override val hostVersion = "test"
        override val workingDirectory = "/w"

        override fun env(name: String): String? = null
    }

    @Test
    fun `rule module defaults delegate to the plain validate`() =
        runTest {
            val rule = Rule()
            assertNull(rule.usage)
            assertNull(rule.ruleExplanation("r"))
            assertEquals(emptyList(), rule.supportedEnrichment)
            assertEquals(emptyList(), rule.requiredEnrichment)
            assertEquals(emptySet(), rule.requiredMergeRequestFields("r"))
            assertFalse(rule.usesExtensionComputedConfig)
            assertNull(rule.executionTimeout)
            assertNull(rule.tokensConsumed())
            rule.logCostSummary()
            assertTrue(rule.initialize(analyzerContextPlugin, emptyMap()))
            assertEquals("r", rule.validate("r", mr, JsonNull).single().rule)
        }

    @Test
    fun `reporter output and format modules key their config by format`() =
        runTest {
            val reporter = Reporter()
            assertEquals("fmt", reporter.configKey)
            assertEquals(JsonObject(emptyMap()), reporter.runtimeReporterConfig(JsonNull, JsonNull))
            assertFalse(reporter.requiresPlatformContext)
            assertTrue(reporter.hasExternalSideEffects)
            assertFalse(reporter.supportsLiveMode)
            assertFalse(reporter.isDefaultReporter || reporter.isDefaultInteractive || reporter.isDefaultCI || reporter.supportsFileOutput)

            val output = Output()
            assertEquals("out", output.configKey)
            assertEquals(emptySet(), output.acceptedFormats)
            assertFalse(
                output.requiresPlatformContext || output.supportsFileOutput || output.isDefaultInteractive || output.isDefaultPlatform
            )
            assertNull(output.preferredFormat)
            assertTrue(output.hasExternalSideEffects)

            val format = Format()
            assertEquals("fmt", format.configKey)
            assertFalse(format.isDefaultInteractive || format.isDefaultPlatform)
        }

    @Test
    fun `pipeline extension hooks are identity and no-ops`() =
        runTest {
            val ext = Extension()
            val findings = listOf(finding)
            ext.onConfigured(PipelineEnvironment(null, null, "stats"))
            ext.onPostFetch(mr, null)
            assertSame(findings, ext.afterValidation(findings, mr, null))
            assertSame(findings, ext.beforeReporting(findings, mr))
            ext.onPipelineComplete()
            assertNull(ext.costSummary())
            assertNull(ext.fetchOptions(null))
            assertFalse(ext.needsBlame(null) || ext.needsHeadContent(null))
            assertEquals(emptySet(), ext.baseFiles(null))
            assertEquals(emptySet(), ext.excludedRules(null, setOf("r")))
            ext.onBaseFiles(emptyMap())
            assertNull(ext.getCachedFindings(mr, null, NoOpLogger))
            ext.invalidateRules(setOf("r"))
            ext.saveIncrementalState(mr, findings)
            assertNull(ext.incrementalChangedFiles())
            assertNull(ext.incrementalCachedFindings())
            assertNull(ext.computeRuleModuleConfig(null))
            assertNull(PipelineEnvironment(null, null, "stats").platformId)
        }

    @Test
    fun `profile enrichment script and cli command defaults`() =
        runTest {
            val profile = object : Stub(), ProfileModule {
                override val profileId = "p"
                override val summary = "s"

                override suspend fun ruleConfigs() = emptyMap<String, JsonElement>()
            }
            assertEquals(emptySet(), profile.tags())
            assertFalse(profile.isDefaultZeroConfigProfile)
            assertEquals(Severity.WARNING, profile.defaultSeverity)
            assertEquals(emptyMap(), profile.reporterConfigs())
            assertEquals(emptyList(), profile.policies())
            assertEquals(ModuleType.PROFILE, profile.type)

            val enrichment = Enrichment()
            assertEquals(emptySet(), enrichment.providedEnrichment)
            assertEquals(emptyList(), enrichment.requiredEnrichment())
            assertNull(enrichment.skipReason())

            val script = object : Stub(), ScriptModule {
                override suspend fun execute(
                    scripts: List<JsonElement>,
                    mr: MergeRequest,
                    context: PluginContext,
                ) = emptyList<Finding>()
            }
            val cli = object : Stub(), CliCommandModule {
                override suspend fun cliCommands() = listOf(PluginCommandSpec("c", "h"))
            }
            assertEquals(ModuleType.SCRIPT, script.type)
            assertEquals(ModuleType.CLI_COMMAND, cli.type)
            assertTrue(cli.shouldInitializeDuringBootstrap())
        }

    @Test
    fun `config format reads extends only from an object`() =
        runTest {
            val withExtends = ConfigFormat(JsonObject(mapOf("extends" to JsonPrimitive("base"))))
            assertEquals(JsonPrimitive("base"), withExtends.parseExtendsOnly(""))
            assertNull(ConfigFormat(JsonObject(emptyMap())).parseExtendsOnly(""))
            assertNull(ConfigFormat(JsonArray(emptyList())).parseExtendsOnly(""))
            assertEquals(ConfigFormatModule.DEFAULT_PRIORITY, withExtends.priority)
            assertEquals(emptySet(), withExtends.fileExtensions)
            assertSame(withExtends, withExtends.withRuleSchemas(JsonObject(emptyMap())))
        }

    @Test
    fun `descriptor lists every implemented type and its host flags`() {
        val cases: Map<Module, Pair<List<String>, Set<String>>> = mapOf(
            Rule() to (listOf("RULE") to setOf("supportedEnrichment", "usesExtensionComputedConfig")),
            Analyzer() to (listOf("AI_ANALYZER") to setOf("needsScopeDetection", "needsBlame", "mrLevel", "enabledByDefault")),
            Reporter() to (listOf("REPORTER") to setOf("isDefaultCI", "supportsLiveMode", "hasExternalSideEffects")),
            Output() to (listOf("OUTPUT") to setOf("acceptedFormats", "preferredFormat", "isDefaultPlatform")),
            Format() to (listOf("FORMAT") to setOf("isDefaultInteractive", "isDefaultPlatform")),
            Extension() to (listOf("PIPELINE_EXTENSION") to setOf("needsBlame", "needsHeadContent")),
            Enrichment() to (listOf("ENRICHMENT_PROVIDER") to setOf("requiredEnrichment")),
            Report() to (listOf("AI_REPORT") to setOf("requiredInputs")),
            Metric() to (listOf("AI_METRIC") to setOf("requiredInputs")),
            Provider() to (listOf("AI_PROVIDER") to setOf("defaultEnabled")),
        )
        cases.forEach { (module, expected) ->
            val descriptor = ModuleDescriptor.of(module)
            assertEquals(module.id, descriptor.id)
            assertEquals(expected.first, descriptor.types, module.id)
            assertTrue(descriptor.properties.keys.containsAll(expected.second), "${module.id}: ${descriptor.properties.keys}")
        }
        assertEquals(
            listOf("FINDINGS"),
            ModuleDescriptor
                .of(Report())
                .properties["requiredInputs"]
                ?.jsonArray
                ?.map { it.toString().trim('"') }
        )
    }

    @Test
    fun `descriptor of a composite module lists every interface with the primary type first`() {
        val composite = object : Stub("combo"), RuleModule, ReporterModule {
            override val type = ModuleType.RULE
            override val formatId = "combo"
            override val configKey = "combo"

            override suspend fun contributedRules() = setOf("x")

            override suspend fun validate(
                ruleId: String,
                mr: MergeRequest,
                configElement: JsonElement,
            ) = emptyList<Finding>()

            override suspend fun report(
                findings: List<Finding>,
                passed: Boolean,
                config: JsonElement,
            ): String? = null
        }

        val descriptor = ModuleDescriptor.of(composite)

        assertEquals(listOf("RULE", "REPORTER"), descriptor.types)
        assertEquals(JsonPrimitive(true), descriptor.properties.jsonObject["defaultEnabled"])
        assertTrue("isDefaultCI" in descriptor.properties)
    }

    @Test
    fun `registry typed accessors filter and sort registered modules`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val modules =
                listOf(Rule(), Reporter(), Output(), Format(), Extension(), Enrichment(), Report(), Metric(), Provider(), Analyzer())
            modules.forEach { registry.register(it) }

            assertEquals(listOf("rule"), registry.ruleModules().map { it.id })
            assertEquals(listOf("reporter"), registry.reporters().map { it.id })
            assertEquals(listOf("output"), registry.outputs().map { it.id })
            assertEquals(listOf("format"), registry.formats().map { it.id })
            assertEquals(listOf("ext"), registry.pipelineExtensions().map { it.id })
            assertEquals(listOf("enrich"), registry.enrichmentProviders().map { it.id })
            assertEquals(listOf("report"), registry.aiReports().map { it.id })
            assertEquals(listOf("metric"), registry.aiMetrics().map { it.id })
            assertEquals("provider", registry.findAiProvider("p")?.id)
            assertNull(registry.findAiProvider("none"))
            assertEquals("analyzer", registry.findAiAnalyzer("a")?.id)
            assertEquals(emptyList(), registry.platformServices())
            assertEquals(emptyList(), registry.scriptModules())
            assertEquals(emptyList(), registry.cliCommandModules())
            assertEquals(emptyList(), registry.configFormats())
            assertEquals(emptyList(), registry.aiInterceptors())
            assertNull(registry.aiTokenAllocator())
            assertEquals(modules.map { it.id }.toSet(), registry.allModules().map { it.id }.toSet())
            registry.forRun().close()
        }
}
