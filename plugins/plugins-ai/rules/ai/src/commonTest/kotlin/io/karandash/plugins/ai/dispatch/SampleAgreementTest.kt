@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiService
import io.karandash.api.run.RunDump
import io.karandash.api.run.RunServiceKeys
import io.karandash.logger.Logger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.pipeline.AgreementConfig
import io.karandash.plugins.ai.core.pipeline.ReviewSamplingConfig
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ai.review-comments.samples` and `ai.agreement`: matching, merging, failed samples, the budget and the dump. */
class SampleAgreementTest {
    private val config = AgreementConfig()

    @Test
    fun `words keeps lowercase words of four letters or more`() {
        assertEquals(setOf("null", "check", "missing", "here"), SampleAgreement.words("Null check is missing here, ok?"))
    }

    @Test
    fun `overlap is the shared words over the smaller set and 0 for an empty set`() {
        assertEquals(1.0, SampleAgreement.overlap(setOf("null", "check"), setOf("null", "check", "missing")))
        assertEquals(0.5, SampleAgreement.overlap(setOf("null", "check"), setOf("null", "other", "words")))
        assertEquals(0.0, SampleAgreement.overlap(emptySet(), setOf("null")))
    }

    @Test
    fun `comments match on the same file within the line window with similar words`() {
        val a = comment("A.kt", 10, "Possible null dereference of user")
        assertEquals(1.0, SampleAgreement.similarity(a, comment("A.kt", 13, "null dereference of user possible"), config))
        assertNull(SampleAgreement.similarity(a, comment("A.kt", 14, "Possible null dereference of user"), config))
        assertNull(SampleAgreement.similarity(a, comment("B.kt", 10, "Possible null dereference of user"), config))
        assertNull(SampleAgreement.similarity(a, comment("A.kt", 10, "Rename this variable entirely"), config))
        assertNull(SampleAgreement.similarity(a, comment("A.kt", 10, "Possible null dereference of user", "PERF"), config))
    }

    @Test
    fun `lineWindow and minSimilarity change what matches`() {
        val a = comment("A.kt", 10, "Possible null dereference of user")
        val far = comment("A.kt", 15, "Possible null dereference of user")
        val loose = comment("A.kt", 10, "Possible leak somewhere else entirely")

        assertEquals(1.0, SampleAgreement.similarity(a, far, AgreementConfig(lineWindow = 5)))
        assertNull(SampleAgreement.similarity(a, loose, config))
        assertTrue((SampleAgreement.similarity(a, loose, AgreementConfig(minSimilarity = 0.2)) ?: 0.0) > 0.0)
    }

    @Test
    fun `two equal sample 1 comments are decided apart`() {
        val twin = comment("A.kt", 10, "Possible null dereference of user")
        val merged = SampleAgreement.merge(listOf(listOf(twin, twin), listOf(comment("A.kt", 11, "null dereference of user here"))), config)

        assertEquals(
            listOf(SampleAgreement.AGREED, SampleAgreement.SINGLE),
            merged.findings.map { it.metadata[AiFindingMetadataKeys.AGREEMENT] },
        )
        assertEquals(listOf(SampleAgreement.AGREED, AgreementConfig.DEMOTE), merged.records.map { it.decision })
    }

    @Test
    fun `agreed comments keep sample 1 and are marked agreed`() {
        val first = comment("A.kt", 10, "Possible null dereference of user")
        val merged = SampleAgreement.merge(listOf(listOf(first), listOf(comment("A.kt", 11, "null dereference of user here"))), config)

        val kept = merged.findings.single()
        assertEquals(first.message, kept.message)
        assertEquals(Location.FileRef("A.kt", 10), kept.location)
        assertEquals(SampleAgreement.AGREED, kept.metadata[AiFindingMetadataKeys.AGREEMENT])
        assertEquals("2/2", kept.metadata[AiFindingMetadataKeys.SAMPLES])
        assertEquals(listOf(SampleAgreement.AGREED), merged.records.map { it.decision })
        assertEquals(listOf(1, 2), merged.records.single().samples)
    }

    @Test
    fun `a singleton is demoted to an INFO file-level note by default`() {
        val bug = comment("A.kt", 10, "Possible null dereference of user", "BUG", Severity.ERROR)
        val merged = SampleAgreement.merge(listOf(listOf(bug), emptyList()), config)

        val note = merged.findings.single()
        assertEquals(Severity.INFO, note.severity)
        assertEquals(Location.FileRef("A.kt", null), note.location)
        assertEquals("10", note.metadata[AiFindingMetadataKeys.LLM_LINE])
        assertEquals(SampleAgreement.SINGLE, note.metadata[AiFindingMetadataKeys.AGREEMENT])
        assertEquals("1/2", note.metadata[AiFindingMetadataKeys.SAMPLES])
        assertEquals(AgreementConfig.DEMOTE, merged.records.single().decision)
    }

    @Test
    fun `singleton drop removes it and keep leaves it as is but marked single`() {
        val one = comment("A.kt", 10, "Possible null dereference of user")
        val later = comment("B.kt", 5, "Unclosed stream leaks a file handle")

        val dropped = SampleAgreement.merge(listOf(listOf(one), listOf(later)), AgreementConfig(singleton = AgreementConfig.DROP))
        assertEquals(emptyList(), dropped.findings)
        assertEquals(listOf(AgreementConfig.DROP, AgreementConfig.DROP), dropped.records.map { it.decision })

        val kept = SampleAgreement.merge(listOf(listOf(one), listOf(later)), AgreementConfig(singleton = AgreementConfig.KEEP))
        assertEquals(listOf(one.message, later.message), kept.findings.map { it.message })
        assertEquals(listOf(Severity.WARNING, Severity.WARNING), kept.findings.map { it.severity })
        assertEquals(setOf(SampleAgreement.SINGLE), kept.findings.map { it.metadata[AiFindingMetadataKeys.AGREEMENT] }.toSet())
    }

    @Test
    fun `a demoted comment takes its paired code suggestion and other findings pass through`() {
        val single = comment("A.kt", 10, "Possible null dereference of user")
        val suggestion = Finding(Severity.INFO, "use a safe call", AiRuleIds.CODE_SUGGESTIONS, Location.FileRef("A.kt", 10))
        val other = Finding(Severity.INFO, "risk", "risk-scoring")

        val merged = SampleAgreement.merge(listOf(listOf(single, suggestion, other), emptyList()), config)

        assertEquals(listOf(AiRuleIds.REVIEW_COMMENTS, "risk-scoring"), merged.findings.map { it.rule })
    }

    @Test
    fun `sampled warns and keeps sample 1 when a later sample fails parses badly or is empty`() =
        runTest {
            val first = listOf(comment("A.kt", 10, "Possible null dereference of user"))
            val parseError = Finding(
                Severity.WARNING,
                "bad",
                AiRuleIds.REVIEW_COMMENTS,
                metadata = mapOf(WellKnownMetadataKeys.ERROR to AiRuleAnalyzerUtils.PARSE_ERROR),
            )
            val cases = mapOf<String, suspend (AiService) -> List<Finding>>(
                "sample 2 of 2 failed (boom)" to { throw AiProviderException("boom") },
                "sample 2 of 2 did not parse" to { listOf(parseError) },
                "sample 2 of 2 returned no comments" to { emptyList() },
            )
            cases.forEach { (cause, sample) ->
                val logger = WarnLogger()
                val kept = SampleAgreement.sampled(input(service = Replies(emptyList())), first, logger, sample)

                assertEquals(first, kept, cause)
                assertEquals(
                    listOf("[ai] rule=review-comments: $cause; kept sample 1 without agreement (ai.review-comments.samples)"),
                    logger.warnings,
                )
            }
        }

    @Test
    fun `samples 1 and other rules return sample 1 untouched without a second call`() =
        runTest {
            val first = listOf(comment("A.kt", 10, "Possible null dereference of user"))
            val calls = mutableListOf<AiService>()
            val one = input(service = Replies(emptyList()), samples = 1)

            assertEquals(
                first,
                SampleAgreement.sampled(one, first, WarnLogger()) {
                    calls += it
                    emptyList()
                }
            )
            assertEquals(
                first,
                SampleAgreement.sampled(input(rule = "risk-scoring"), first, WarnLogger()) {
                    calls += it
                    emptyList()
                }
            )
            assertEquals(emptyList(), calls)
        }

    @Test
    fun `extra samples cost the prompt at the cached-read price and the output in full`() {
        val cfg = AiPluginConfig()

        assertEquals(0, SampleAgreement.extraTokens("risk-scoring", cfg, "gpt-4o", 1_000, 1, 200))
        assertEquals(0, SampleAgreement.extraTokens(AiRuleIds.REVIEW_COMMENTS, ONE.cfg(), "gpt-4o", 1_000, 1, 200))
        val two = SampleAgreement.extraTokens(AiRuleIds.REVIEW_COMMENTS, cfg, "gpt-4o", 1_000, 1, 200)
        assertEquals(400 + 200, two)
        val three = SampleAgreement.extraTokens(AiRuleIds.REVIEW_COMMENTS, ReviewSamplingConfig(3).cfg(), "gpt-4o", 1_000, 1, 200)
        assertEquals(two * 2, three)
    }

    @Test
    fun `the dispatcher asks twice at the sample temperature and dumps agreement json`() =
        runTest {
            val service = Replies(
                listOf(
                    listOf(
                        comment("A.kt", 1, "Possible null dereference of user"),
                        comment("A.kt", 1, "Unclosed stream leaks handle", "PERF")
                    ),
                    listOf(comment("A.kt", 2, "null dereference of user possible")),
                ),
            )
            val dumps = mutableListOf<Pair<String, String>>()
            val session = ModuleRegistryImpl().forRun()
            session.provide(RunServiceKeys.DUMP, RunDump { name, content -> dumps += name to content })

            val result = withContext(session.services) { dispatcher(service).dispatch(input(service = service)) }

            assertEquals(2, service.temperatures.size)
            assertEquals(0.5, service.temperatures[1])
            val byAgreement = result.findings.associateBy { it.metadata[AiFindingMetadataKeys.AGREEMENT] }
            assertEquals("Possible null dereference of user", byAgreement.getValue(SampleAgreement.AGREED).message)
            assertEquals(Severity.INFO, byAgreement.getValue(SampleAgreement.SINGLE).severity)
            val agreement = dumps.single { it.first == "ai/stages/agreement.json" }.second
            assertTrue("\"decision\": \"agreed\"" in agreement, agreement)
            assertTrue("\"decision\": \"demote\"" in agreement, agreement)
        }

    @Test
    fun `the dispatcher asks once with samples 1 and keeps findings unmarked`() =
        runTest {
            val service = Replies(listOf(listOf(comment("A.kt", 1, "Possible null dereference of user"))))

            val result = dispatcher(service).dispatch(input(service = service, samples = 1))

            assertEquals(1, service.temperatures.size)
            assertNull(result.findings.single().metadata[AiFindingMetadataKeys.AGREEMENT])
            assertEquals(Severity.WARNING, result.findings.single().severity)
        }

    private fun dispatcher(replies: Replies): AiRuleDispatcher {
        val analyzer = ScriptedAnalyzer(replies)
        val registry = object : ModuleRegistry by buildTestRegistry() {
            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { analyzerId == it.analyzerId }
        }
        return AiRuleDispatcher(registry, WarnLogger())
    }

    private fun input(
        service: AiService? = Replies(emptyList()),
        samples: Int = 2,
        rule: String = AiRuleIds.REVIEW_COMMENTS,
    ) = AiRuleDispatcher.DispatchInput(
        ruleId = rule,
        mr = createTestMergeRequest(
            changes = listOf(FileChange("A.kt", changeType = ChangeType.MODIFIED, additions = 1, deletions = 1, diffContent = DIFF)),
        ),
        concurrencySemaphore = Semaphore(1),
        aiProvider = UnusedProvider,
        budget = TokenBudget(maxTokens = 100_000),
        model = "m",
        config = JsonObject(emptyMap()),
        resolvedPrompt = null,
        aiService = service,
        aiRuleConfig = AiRuleConfig(),
        pluginCfg = AiPluginConfig(structuredOutput = false, reviewSampling = ReviewSamplingConfig(samples = samples)),
        contextProcessors = emptyList(),
        accumulatedFindings = emptyList(),
        customRules = emptyList(),
    )

    private fun ReviewSamplingConfig.cfg() = AiPluginConfig(reviewSampling = this)

    /** Answers each call with the next scripted findings; records each request's temperature. */
    private class Replies(
        val scripts: List<List<Finding>>,
    ) : AiService {
        val temperatures = mutableListOf<Double>()
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            temperatures += request.temperature
            return AiCompletionResponse("[]", 1, 1, 2, request.model)
        }

        fun findingsOf(call: Int): List<Finding> = scripts.getOrElse(call) { emptyList() }
    }

    private class ScriptedAnalyzer(
        private val replies: Replies,
    ) : AiAnalyzerModule {
        override val analyzerId: String = AiRuleIds.REVIEW_COMMENTS
        override val id: String = analyzerId
        override val name: String = analyzerId
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = analyzerId
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = 0

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
            val call = replies.temperatures.size
            context.aiService.complete(AiCompletionRequest(prompt = "review", model = "m"))
            return replies.findingsOf(call)
        }
    }

    private object UnusedProvider : AiProvider {
        override val providerId = "unused"

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse = error("analyzers call through AiService")

        override suspend fun close() = Unit
    }

    private class WarnLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private companion object {
        const val DIFF = "@@ -1,2 +1,2 @@\n-old line\n+new line\n context"
        val ONE = ReviewSamplingConfig(samples = 1)

        fun comment(
            path: String,
            line: Int,
            message: String,
            category: String = "BUG",
            severity: Severity = Severity.WARNING,
        ) = Finding(
            severity = severity,
            message = message,
            rule = AiRuleIds.REVIEW_COMMENTS,
            location = Location.FileRef(path, line),
            metadata = mapOf(AiFindingMetadataKeys.CATEGORY to category),
        )
    }
}
