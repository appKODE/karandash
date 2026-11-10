package io.karandash.eval

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.pipeline.ValidationOptions
import io.karandash.cli.pipeline.ValidationPipeline
import io.karandash.cli.pipeline.ValidationPipelineResult
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.outputs.PlatformOutputModule
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Replays one corpus case through the real `validate` pipeline ([ValidationPipeline]): config
 * loading, the AI rule and its analyzers, the AI pipeline extension hooks, markdown formatting and
 * platform output. Only the edges are fake: the LLM ([CassetteInterceptor]) and the platform API
 * ([CassettePlatform]). Each call uses a fresh temp dir, so no cache or stats leak between runs.
 */
class PipelineReplay(
    private val analyzerIds: Map<String, AiAnalyzerModule>,
) {
    suspend fun replay(
        case: EvalCase,
        recorded: EvalRunner.Responder,
    ): CaseReplay {
        val dir = TestFiles.createTestTempDirectory("karandash-eval-${case.id}")
        SystemFileSystem.createDirectories(dir)
        val configPath = Path(dir, "config.json")
        EvalRunner.write(configPath, KarandashJson.Pretty.encodeToString(JsonObject.serializer(), config(case, dir)))

        val interceptor = CassetteInterceptor(case.id) { caseId, analyzerId, request ->
            case.cassette[analyzerId] ?: recorded.respond(caseId, analyzerId, request)
        }
        val provider = CassetteProvider()
        val platform = CassettePlatform(case)
        val logger = ReplayLogger()
        val analyzerFindings = MutableStateFlow<Map<String, Int>>(emptyMap())
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.logger.configure(logger)
        try {
            bootstrap.loadPluginsFromDirs(emptyList())
            val registry = bootstrap.registry()
            registry.register(interceptor)
            registry.register(provider)
            registry.register(platform)
            // A fixed clock keeps the summary's "updated" time out of the replay snapshot.
            registry.register(PlatformOutputModule(FIXED_CLOCK))
            // The design doc reaches design-doc-drift the way a Confluence page does: provider data, then the design-doc block.
            case.designDoc?.let { registry.register(CassetteDesignDoc(it)) }
            registry.aiAnalyzers().filter { it.analyzerId in case.analyzers }.forEach { analyzer ->
                registry.register(
                    CountingAnalyzer(analyzer, case) { count ->
                        analyzerFindings.update { it + (analyzer.analyzerId to (it[analyzer.analyzerId] ?: 0) + count) }
                    },
                )
            }
            val result = ValidationPipeline(options(case, configPath, bootstrap, logger), sharedBootstrap = bootstrap).execute()
            return toReplay(case, result, interceptor, provider, platform, analyzerFindings.value, logger.lines.value)
        } finally {
            bootstrap.shutdown()
            EvalRunner.write(Path("build/reports/ai-eval/replay/${case.id}.log"), logger.lines.value.joinToString("\n", postfix = "\n"))
        }
    }

    private fun toReplay(
        case: EvalCase,
        result: ValidationPipelineResult,
        interceptor: CassetteInterceptor,
        provider: CassetteProvider,
        platform: CassettePlatform,
        analyzerFindings: Map<String, Int>,
        log: List<String>,
    ): CaseReplay {
        val validation = result.validationResult
        val findings = validation?.findings.orEmpty()
        val skipped = validation
            ?.skippedRules
            .orEmpty()
            .associate { it.ruleId to it.reason }
            .toList()
            .sortedBy { it.first }
            .toMap()
        val selfSkipped = case.analyzers
            .mapNotNull { id -> selfSkipReason(id, log)?.let { id to it } }
            .toMap()
            .toList()
            .sortedBy { it.first }
            .toMap()
        val loggedDrops = log.filter { it.startsWith(DROP_LOG) }.groupingBy { it.substringAfterLast("(").removeSuffix(")") }.eachCount()
        // Rules run in parallel: order prompts by content so the snapshot is stable.
        val prompts = interceptor.prompts.value.sortedWith(compareBy({ it.analyzer }, { it.prompt }))
        val prompted = prompts.map { it.analyzer }.toSet()
        // Some analyzers answer locally (no LLM call) with findings; only "nothing and no reason" is silent.
        val silent = case.analyzers
            .filter { it !in prompted && it !in skipped && it !in selfSkipped && (analyzerFindings[it] ?: 0) == 0 }
            .map { "no prompt, no finding and no skip reason: $it" }
        val notes = platform.notes.value.sortedWith(compareBy({ it.file }, { it.line }, { it.body }))
        return CaseReplay(
            caseId = case.id,
            prompts = prompts,
            findings = findings.map(::toReplayFinding).sortedWith(compareBy({ it.rule }, { it.file }, { it.line }, { it.message })),
            summary = platform.summaries.value
                .lastOrNull()
                .orEmpty(),
            inlineNotes = notes,
            counters = ReplayCounters(
                analyzerFindings = analyzerFindings.values.sum(),
                ruleFindings = validation?.ruleExecutionResults.orEmpty().sumOf { it.findings.size },
                afterValidation = findings.size,
                notes = notes.size,
                skippedRules = skipped,
                selfSkipped = selfSkipped,
                loggedDrops = loggedDrops.toList().sortedBy { it.first }.toMap(),
                silentDrops = (silent + interceptor.misses.value + provider.bypasses.value).sorted(),
                estimatedTokens = interceptor.estimatedTokens.value,
            ),
        )
    }

    private fun config(
        case: EvalCase,
        dir: Path,
    ): JsonObject =
        buildJsonObject {
            putJsonObject("cache") { put("dir", Path(dir, "cache").toString()) }
            // The review-flow.md artifact goes to the temp dir too, never into the repo the test runs from.
            putJsonObject("reporters") {
                putJsonObject("markdown") { put("flowArtifactDir", Path(dir, "debug").toString()) }
            }
            putJsonObject("ai") {
                put("provider", CassetteProvider.PROVIDER_ID)
                put("model", EvalRunner.MODEL)
                // run statistics go to the temp dir, never into .karandash/ of the module the test runs from.
                putJsonObject("statistics") { put("dir", Path(dir, "stats").toString()) }
                EvalRunner.EVAL_CONFIG.forEach { (key, value) -> put(key, value) }
                // `ai.<featureKey>` is what AiConfigSynthesizer turns into an enabled rule.
                // fix-suggestions reads the findings accumulated so far: inside one priority group rules run in
                // parallel, so it gets its own later group or its prompt depends on scheduling.
                case.analyzers.forEach { id ->
                    putJsonObject(analyzerIds.getValue(id).featureKey) {
                        put("enabled", true)
                        if (id == FIX_SUGGESTIONS) put("priority", LATE_PRIORITY)
                    }
                }
            }
        }

    private fun options(
        case: EvalCase,
        configPath: Path,
        bootstrap: PluginBootstrap,
        logger: ReplayLogger,
    ): ValidationOptions =
        ValidationOptions(
            configPathStr = configPath.toString(),
            mrDataStr = null,
            mrId = MR_ID,
            platform = CassettePlatform.PLATFORM,
            project = "eval/${case.id}",
            prUrl = null,
            format = "console",
            outputPathStr = null,
            quiet = true,
            verbose = false,
            strict = false,
            dryRun = false,
            explain = false,
            fix = false,
            yes = false,
            noAutoDetect = true,
            outputWriter = {},
            sharedBootstrap = bootstrap,
            mrOverride = EvalRunner
                .toMergeRequest(
                    case
                ).copy(diffRefs = DiffRefs("base-${case.id}", "head-${case.id}", "start-${case.id}")),
            logger = logger,
        )

    /** Analyzers log their own early returns as `[id] Skipped: <why>` or `[id]: skipped - <why>`. */
    private fun selfSkipReason(
        analyzerId: String,
        log: List<String>,
    ): String? =
        log.firstNotNullOfOrNull { line ->
            when {
                "[$analyzerId] Skipped: " in line -> {
                    line.substringAfter("[$analyzerId] Skipped: ")
                }

                "[$analyzerId]: skipped — " in line -> {
                    line.substringAfter("[$analyzerId]: skipped — ")
                }

                else -> {
                    null
                }
            }
        }

    private fun toReplayFinding(finding: Finding): ReplayFinding {
        val ref = finding.location as? Location.FileRef
        return ReplayFinding(finding.rule, finding.severity.name, ref?.path, ref?.line, finding.message)
    }

    /**
     * Delivers [doc] as the one Confluence page under [EnrichmentKeys.CONFLUENCE_PAGES], the way the Confluence
     * provider would; the real [io.karandash.plugins.ai.core.context.DesignDocEnricher] turns it into the block.
     */
    private class CassetteDesignDoc(
        private val doc: String,
    ) : EnrichmentProviderModule {
        override val id: String = "eval-design-doc"
        override val name: String = "Eval design doc"
        override val version: String = "1.0"
        override val description: String = ""

        @OptIn(ExperimentalKarandashApi::class)
        override val providedEnrichment: Set<String> = setOf(EnrichmentKeys.CONFLUENCE_PAGES)

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun enrich(
            mr: MergeRequest,
            config: JsonElement,
            upstreamContext: EnrichmentContext,
        ): Map<String, JsonElement> {
            val page = buildJsonObject {
                put("title", "Design")
                put("markdown", doc)
            }
            return mapOf(EnrichmentKeys.CONFLUENCE_PAGES to buildJsonObject { put("design", page) })
        }
    }

    /** Same id as the built-in analyzer, so registering it replaces the original; counts raw findings and offers the case's platform. */
    private class CountingAnalyzer(
        private val delegate: AiAnalyzerModule,
        private val case: EvalCase,
        private val onFindings: (Int) -> Unit,
    ) : AiAnalyzerModule by delegate {
        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> =
            EvalRunner.withPlatform(case) { delegate.analyze(context) }.also { onFindings(it.size) }
    }

    companion object {
        private const val MR_ID = "1"
        private const val DROP_LOG = "WARN finding-dropped:"
        private const val FIX_SUGGESTIONS = "fix-suggestions"

        /** After the AI default priority (200), see AiConfigSynthesizer. */
        private const val LATE_PRIORITY = 300

        private val FIXED_CLOCK = object : Clock {
            override fun now(): Instant = Instant.parse("2026-10-30T15:40:00Z")
        }
    }
}
