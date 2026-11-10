@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.eval

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiService
import io.karandash.api.run.MergeRequestSearch
import io.karandash.api.run.RepositoryFileReader
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunServiceKeys
import io.karandash.api.run.ServiceKey
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.analyzers.changecategorization.ChangeCategorizationAnalyzerModule
import io.karandash.plugins.ai.analyzers.codequality.CodeQualityAnalyzerModule
import io.karandash.plugins.ai.analyzers.codesuggestions.CodeSuggestionsAnalyzerModule
import io.karandash.plugins.ai.analyzers.commentanalysis.CommentAnalysisAnalyzerModule
import io.karandash.plugins.ai.analyzers.descriptionquality.DescriptionQualityAnalyzerModule
import io.karandash.plugins.ai.analyzers.designdocdrift.DesignDocDriftAnalyzerModule
import io.karandash.plugins.ai.analyzers.fixsuggestions.FixSuggestionsAnalyzerModule
import io.karandash.plugins.ai.analyzers.generatedescription.GenerateDescriptionAnalyzerModule
import io.karandash.plugins.ai.analyzers.mrsummary.MrSummaryAnalyzerModule
import io.karandash.plugins.ai.analyzers.outdateddocs.OutdatedDocsAnalyzerModule
import io.karandash.plugins.ai.analyzers.ownership.OwnershipAnalyzerModule
import io.karandash.plugins.ai.analyzers.prsplit.PrSplitAnalyzerModule
import io.karandash.plugins.ai.analyzers.reviewcomments.ReviewCommentsAnalyzerModule
import io.karandash.plugins.ai.analyzers.riskscoring.RiskScoringAnalyzerModule
import io.karandash.plugins.ai.analyzers.similarissues.SimilarIssuesAnalyzerModule
import io.karandash.plugins.ai.core.DiffContextBuilder
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.context.DesignDocEnricher
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Runs every corpus case through the real analyzers. [responder] answers each analyzer's LLM call:
 * replay from the recordings, or a live provider. Returns the report and the raw responses seen
 * (what `KARANDASH_EVAL_RECORD=1` writes back).
 */
class EvalRunner(
    private val evalDir: Path = Path("src/commonTest/resources/eval"),
) {
    /** Answers one analyzer call for one case; `null` means "no recording". */
    fun interface Responder {
        suspend fun respond(
            caseId: String,
            analyzerId: String,
            request: AiCompletionRequest,
        ): String?
    }

    @Serializable
    data class Run(
        val report: EvalReport,
        val responses: Map<String, Map<String, String>>,
    )

    val analyzers: Map<String, AiAnalyzerModule> = listOf(
        ChangeCategorizationAnalyzerModule(),
        CodeQualityAnalyzerModule(),
        CodeSuggestionsAnalyzerModule(),
        CommentAnalysisAnalyzerModule(),
        DescriptionQualityAnalyzerModule(),
        DesignDocDriftAnalyzerModule(),
        FixSuggestionsAnalyzerModule(),
        GenerateDescriptionAnalyzerModule(),
        MrSummaryAnalyzerModule(),
        OutdatedDocsAnalyzerModule(),
        OwnershipAnalyzerModule(),
        PrSplitAnalyzerModule(),
        ReviewCommentsAnalyzerModule(),
        RiskScoringAnalyzerModule(),
        SimilarIssuesAnalyzerModule(),
    ).associateBy { it.analyzerId }

    fun loadCases(): List<EvalCase> =
        SystemFileSystem
            .list(Path(evalDir, "corpus"))
            .filter { it.name.endsWith(".json") }
            .sortedBy { it.name }
            .map { KarandashJson.Strict.decodeFromString(EvalCase.serializer(), read(it)) }

    fun loadRecording(caseId: String): EvalRecording {
        val path = Path(evalDir, "recorded", "$caseId.json")
        if (!SystemFileSystem.exists(path)) return EvalRecording()
        return KarandashJson.Strict.decodeFromString(EvalRecording.serializer(), read(path))
    }

    fun loadBaseline(): EvalReport = KarandashJson.Default.decodeFromString(EvalReport.serializer(), read(Path(evalDir, "baseline.json")))

    /** Replay responder: recorded JSON objects are sent as their compact text, strings verbatim. */
    fun replay(): Responder {
        // Rules run in parallel and Kotlin/Native HashMap is not thread-safe: copy-on-write, a lost race only reloads the file.
        val cache = MutableStateFlow<Map<String, EvalRecording>>(emptyMap())
        return Responder { caseId, analyzerId, _ ->
            val recording = cache.value[caseId] ?: loadRecording(caseId).also { loaded -> cache.update { it + (caseId to loaded) } }
            val element = recording.responses[analyzerId]
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: element?.toString()
        }
    }

    suspend fun run(
        responder: Responder,
        mode: String,
    ): Run {
        val scores = analyzers.keys.associateWith { EvalScore() }.toMutableMap()
        val misses = mutableListOf<String>()
        val seen = mutableMapOf<String, MutableMap<String, String>>()
        val cases = loadCases()
        for (case in cases) {
            val mr = toMergeRequest(case)
            val diffContext = DiffContextBuilder.build(mr, AiRuleConfig.fromPluginConfig(EVAL_CONFIG))
            for (analyzerId in case.analyzers) {
                val analyzer = requireNotNull(analyzers[analyzerId]) { "${case.id}: unknown analyzer $analyzerId" }
                val service = CapturingService { request ->
                    val content = checkNotNull(responder.respond(case.id, analyzerId, request)) {
                        "${case.id}: no recorded response for $analyzerId (re-record with KARANDASH_EVAL_RECORD=1)"
                    }
                    seen.getOrPut(case.id) { mutableMapOf() }[analyzerId] = content
                    content
                }
                analyzer.initialize(TestPluginContext(EVAL_CONFIG))
                val context = AiAnalyzerContext(
                    mr = mr,
                    aiService = service,
                    budget = TokenBudget(EVAL_TOKEN_BUDGET),
                    model = MODEL,
                    config = EVAL_CONFIG,
                    contextBlocks = listOfNotNull(
                        case.designDoc?.let { AiContextBlock("", it, DesignDocEnricher.BLOCK_PRIORITY, DesignDocEnricher.MODULE_ID) },
                    ),
                    accumulatedFindings = case.priorFindings.map(::toFinding),
                    diffContext = diffContext,
                )
                val findings = withPlatform(case) { analyzer.analyze(context) }
                val outcome = EvalScorer.score(analyzerId, findings, case.expected)
                scores[analyzerId] = scores.getValue(analyzerId) + outcome.score
                outcome.misses.forEach { misses += "${case.id}: $it" }
            }
        }
        val overall = scores.values.fold(EvalScore()) { acc, score -> acc + score }
        val report = EvalReport(
            mode = mode,
            overall = overall.toMetrics(),
            analyzers = scores
                .mapValues { it.value.toMetrics() }
                .toList()
                .sortedBy { it.first }
                .toMap(),
            misses = misses,
            coverage = EvalCoverage(
                cases = cases.size,
                analyzers = cases.flatMap { it.analyzers }.toSet().size,
                labels = cases.sumOf { it.expected.size },
            ),
        )
        return Run(report, seen)
    }

    /** Every case through the full `validate` pipeline (see [PipelineReplay]); cassette first, then recordings. */
    suspend fun replayPipeline(): List<CaseReplay> {
        val pipeline = PipelineReplay(analyzers)
        val recorded = replay()
        return loadCases().map { pipeline.replay(it, recorded) }
    }

    fun writeRecordings(responses: Map<String, Map<String, String>>) {
        responses.forEach { (caseId, byAnalyzer) ->
            val recording = EvalRecording(
                byAnalyzer
                    .mapValues { (_, text) -> parseOrString(text) }
                    .toList()
                    .sortedBy { it.first }
                    .toMap()
            )
            write(
                Path(evalDir, "recorded", "$caseId.json"),
                KarandashJson.Pretty.encodeToString(EvalRecording.serializer(), recording) + "\n",
            )
        }
    }

    private fun parseOrString(text: String): JsonElement =
        try {
            KarandashJson.Strict.parseToJsonElement(text).takeIf { it is JsonObject } ?: JsonPrimitive(text)
        } catch (_: SerializationException) {
            // Not JSON (e.g. fenced or prose output): keep the raw text so replay sends it unchanged.
            JsonPrimitive(text)
        }

    private class CapturingService(
        private val answer: suspend (AiCompletionRequest) -> String,
    ) : AiService {
        override val isAvailable: Boolean = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            val content = answer(request)
            val promptTokens = request.prompt.length / CHARS_PER_TOKEN
            val completionTokens = content.length / CHARS_PER_TOKEN
            return AiCompletionResponse(content, promptTokens, completionTokens, promptTokens + completionTokens, MODEL)
        }
    }

    companion object {
        const val MODEL = "eval-replay"
        private const val CHARS_PER_TOKEN = 4
        private const val EVAL_TOKEN_BUDGET = 1_000_000
        private const val SPLIT_THRESHOLD = 3

        /** One config for every case: separate code suggestions, and pr-split armed on small MRs. */
        val EVAL_CONFIG: JsonObject = JsonObject(
            mapOf(
                "codeSuggestionMode" to JsonPrimitive("separate"),
                "splitThreshold" to JsonPrimitive(SPLIT_THRESHOLD),
            ),
        )

        /**
         * Runs [block] with the run services the case's platform offers: its repository files and merge-request search.
         * The replay passes the MR via `mrOverride`, which resolves no platform, so analyzers get them here.
         */
        suspend fun <T> withPlatform(
            case: EvalCase,
            block: suspend () -> T,
        ): T {
            val session = ModuleRegistryImpl().forRun()
            // Inside the replayed pipeline the run's own services (cache, logger, enrichment, ...) stay visible.
            val outer = RunScopedServices.current()

            fun <S : Any> keep(key: ServiceKey<S>) = outer?.get(key)?.let { session.provide(key, it) }
            keep(RunServiceKeys.CACHE_PROVIDER)
            keep(RunServiceKeys.SUBPROCESS_BUDGET)
            keep(RunServiceKeys.LOGGER)
            keep(RunServiceKeys.PATTERN_COMPILER)
            keep(RunServiceKeys.PIPELINE_EXTENSIONS)
            keep(RunServiceKeys.RULE_VALIDATOR)
            keep(RunServiceKeys.OPEN_MERGE_REQUESTS)
            keep(RunServiceKeys.DISMISSED_INLINE_COMMENTS)
            if (case.reviewThreads.isEmpty()) {
                keep(
                    RunServiceKeys.REVIEW_THREADS
                )
            } else {
                session.provide(RunServiceKeys.REVIEW_THREADS, CaseThreads(case))
            }
            keep(RunServiceKeys.ENRICHMENT)
            val platform = CassettePlatform(case)
            val files = RepositoryFileReader { ref, path -> platform.fetchFileContent("", ref, path) }
            if (platform.supportsFileContent) session.provide(RunServiceKeys.REPOSITORY_FILE, files)
            val search = MergeRequestSearch { text, author, limit -> platform.searchMergeRequests("", text, author, limit) }
            session.provide(RunServiceKeys.MERGE_REQUEST_SEARCH, search)
            return try {
                withContext(session.services) { block() }
            } finally {
                session.close()
            }
        }

        fun toMergeRequest(case: EvalCase): MergeRequest =
            createTestMergeRequest(
                title = case.title,
                description = case.description,
                sourceBranch = case.sourceBranch,
                labels = case.labels,
                commits = case.commits.mapIndexed { index, message ->
                    Commit("${case.id}-$index", message, CommitAuthor("Eval Dev", "dev@example.com"), "2026-01-01T00:00:00Z")
                },
                changes = case.files.map { file ->
                    FileChange(
                        path = file.path,
                        changeType = ChangeType.valueOf(file.changeType),
                        additions = file.diff.count { it.startsWith("+") },
                        deletions = file.diff.count { it.startsWith("-") },
                        diffContent = file.diff.joinToString("\n"),
                    )
                },
            ).copy(id = case.id)

        private fun toFinding(prior: EvalPriorFinding): Finding =
            Finding(severity = Severity.valueOf(prior.severity), message = prior.message, rule = prior.rule)

        private fun read(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }

        fun write(
            path: Path,
            text: String,
        ) {
            path.parent?.let { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
        }
    }
}
