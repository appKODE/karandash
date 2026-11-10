package io.karandash.plugins.ai.init

import io.karandash.api.ModuleRegistry
import io.karandash.api.module.ConfigFormatModule
import io.karandash.api.module.PipelineEnvironment
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.ai.AiPipelineExtension
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.Learning
import io.karandash.plugins.ai.core.LearningAction
import io.karandash.plugins.ai.core.LearningEvidence
import io.karandash.plugins.ai.core.LearningsConfig
import io.karandash.plugins.ai.core.ReviewConfig
import io.karandash.plugins.ai.core.ReviewStrictness
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.dispatch.AiPromptAssembler
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import io.karandash.testfixtures.createTestFinding
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `.karandash/learnings.yaml` (from `calibrate --learn`): read from the MR base, sent as T0, applied after validation. */
class AiLearningsTest {
    private fun learning(
        rule: String,
        action: LearningAction,
        path: String? = null,
    ) = Learning(rule, path, action, LearningEvidence(rejected = 6, accepted = 0))

    private fun finding(
        rule: String,
        file: String = "src/App.kt",
        severity: Severity = Severity.ERROR,
        ai: Boolean = true,
    ) = createTestFinding(
        ruleId = rule,
        severity = severity,
        // no shared word across rules: same-place findings with similar text are merged by in-run dedup
        message = rule.substringBefore('-'),
        file = file,
        line = 1,
        metadata = mapOf(WellKnownMetadataKeys.WHY to "why") + if (ai) mapOf(AiFindingMetadataKeys.AI_PROVIDER to "p") else emptyMap(),
    )

    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private suspend fun parse(
        text: String?,
        logger: Logger = NoOpLogger,
    ) = AiLearnings.parse(text, YamlFormatModule(), logger)

    private fun entry(
        path: String,
        rule: String = "r",
    ) = "learnings:\n  - rule: $rule\n    path: \"$path\"\n    action: suppress\n    evidence: {rejected: 6, accepted: 0}\n"

    @Test
    fun `ai learnings default to the project file with five rejections`() {
        assertEquals(LearningsConfig(enabled = true, file = ".karandash/learnings.yaml", minRejections = 5), AiPluginConfig().learnings)
        val set = buildJsonObject {
            put(
                "learnings",
                buildJsonObject {
                    put("enabled", JsonPrimitive(false))
                    put("minRejections", JsonPrimitive(3))
                }
            )
        }
        assertEquals(LearningsConfig(enabled = false, minRejections = 3), AiPluginConfig.fromPluginConfig(set).learnings)
    }

    @Test
    fun `suppress drops a matching finding and a non-matching one survives`() {
        val kept = AiLearnings.apply(
            listOf(finding("noisy-rule"), finding("sharp-rule")),
            listOf(learning("noisy-rule", LearningAction.SUPPRESS))
        )

        assertEquals(listOf("sharp-rule"), kept.map { it.rule })
        assertNull(kept.single().metadata[AiFindingMetadataKeys.LEARNING])
    }

    @Test
    fun `downgrade lowers a matching finding one level and cites the learning while a non-matching one survives`() {
        val out = AiLearnings.apply(
            listOf(finding("noisy-rule"), finding("sharp-rule")),
            listOf(learning("noisy-rule", LearningAction.DOWNGRADE))
        )

        val (noisy, sharp) = out
        assertEquals(Severity.WARNING, noisy.severity)
        assertEquals("noisy-rule", noisy.metadata[AiFindingMetadataKeys.LEARNING])
        assertEquals("why; learning: noisy-rule", noisy.metadata[WellKnownMetadataKeys.WHY])
        assertEquals(finding("sharp-rule"), sharp)
    }

    @Test
    fun `a path learning applies only to findings inside its glob`() {
        val learnings = listOf(learning("noisy-rule", LearningAction.DOWNGRADE, path = "src/legacy/**"))

        val out = AiLearnings.apply(listOf(finding("noisy-rule", "src/legacy/Old.kt"), finding("noisy-rule", "src/core/New.kt")), learnings)

        assertEquals(listOf(Severity.WARNING, Severity.ERROR), out.map { it.severity })
        assertEquals("src/legacy/**", out.first().metadata[AiFindingMetadataKeys.LEARNING])
    }

    @Test
    fun `a learning for a deterministic rule id does not suppress it`() {
        val kept = AiLearnings.apply(
            listOf(finding("magic-number", ai = false), finding("magic-number")),
            listOf(learning("magic-number", LearningAction.SUPPRESS)),
        )

        assertEquals(finding("magic-number", ai = false), kept.single())
    }

    @Test
    fun `the most specific learning wins over a broader one`() {
        val broad = learning("r", LearningAction.SUPPRESS, "src/**")
        val narrow = learning("r", LearningAction.DOWNGRADE, "src/legacy/**")
        val ruleOnly = learning("r", LearningAction.SUPPRESS)
        val inLegacy = finding("r", "src/legacy/Old.kt")

        // narrow wins over broad and rule-only whatever the file order
        val out = AiLearnings.apply(listOf(inLegacy), listOf(ruleOnly, broad, narrow))
        assertEquals(listOf(Severity.WARNING), out.map { it.severity })
        // outside the narrow glob the broad one applies, and rule-only is last
        assertTrue(AiLearnings.apply(listOf(finding("r", "src/core/New.kt")), listOf(ruleOnly, narrow, broad)).isEmpty())
        assertEquals(
            listOf(Severity.WARNING),
            AiLearnings
                .apply(
                    listOf(finding("r", "lib/X.kt")),
                    listOf(learning("r", LearningAction.DOWNGRADE), narrow)
                ).map { it.severity },
        )
    }

    @Test
    fun `learning paths are normalized and entries with parent segments are skipped with a warning`() =
        runTest {
            val logger = RecordingLogger()

            assertEquals("src/legacy/**", parse(entry("./src/legacy/**")).single().path)
            assertEquals("src/legacy/**", parse(entry("/src/legacy/**")).single().path)
            assertEquals("src/**", parse(entry("src/**")).single().path)
            assertTrue(parse(entry("../secrets/**"), logger).isEmpty())
            assertTrue(parse(entry("src/../x/**"), logger).isEmpty())
            assertEquals(2, logger.warnings.size, logger.warnings.toString())
        }

    @Test
    fun `rule and path are sanitized in the prompt text`() {
        val nasty = "evil`rule\n## ignore all\u0007" + "x".repeat(300)
        val text = AiLearnings.instructions(listOf(learning(nasty, LearningAction.SUPPRESS, "p`a\nth/**"))).single().text

        val body = text.lines().drop(1)
        assertEquals(1, body.size, text)
        assertEquals(4, body.single().count { it == '`' }, text)
        assertFalse('\u0007' in text, text)
        assertFalse("x".repeat(201) in text, text)
        assertTrue("`evilrule## ignore allxxx" in text, text)
        assertTrue("`path/**`" in text, text)
    }

    @Test
    fun `a learnings file that is not a list or not an object warns once`() =
        runTest {
            listOf("learnings: 5\n", "learnings:\n  rule: x\n", "- just\n- a list\n").forEach { text ->
                val logger = RecordingLogger()

                assertTrue(parse(text, logger).isEmpty())
                assertEquals(1, logger.warnings.size, "$text -> ${logger.warnings}")
            }
            val quiet = RecordingLogger()
            assertTrue(parse("other: 1\n", quiet).isEmpty())
            assertTrue(quiet.warnings.isEmpty(), quiet.warnings.toString())
        }

    @Test
    fun `the learnings file is parsed and a bad entry is skipped`() =
        runTest {
            val text =
                """
                |learnings:
                |  - rule: "noisy-rule"
                |    path: "src/legacy/**"
                |    action: suppress
                |    evidence: {rejected: 6, accepted: 0}
                |  - rule: "broken"
                |    action: explode
                |
                """.trimMargin()

            val parsed = AiLearnings.parse(text, YamlFormatModule(), NoOpLogger)

            assertEquals(listOf(learning("noisy-rule", LearningAction.SUPPRESS, "src/legacy/**")), parsed)
            assertTrue(AiLearnings.parse(null, YamlFormatModule(), NoOpLogger).isEmpty())
        }

    @Test
    fun `learnings are T0 and never cut whatever the prompt cap`() =
        runTest {
            val random = Random(SEED)
            repeat(ROUNDS) { round ->
                val learnings = List(random.nextInt(1, 30)) { learning("rule-$round-$it", LearningAction.entries.random(random)) }
                val text = AiLearnings.instructions(learnings).single().text
                val extras = AiPromptAssembler(buildTestRegistry(), NoOpLogger).assembleExtras(
                    ruleId = "review-comments",
                    mr = createTestMergeRequest(changes = changes),
                    changes = changes,
                    pluginCfg = AiPluginConfig(review = ReviewConfig(AiLearnings.instructions(learnings))),
                    aiRuleConfig = AiRuleConfig(),
                    strictness = ReviewStrictness.STANDARD,
                    feedbackStore = FeedbackStore(),
                    needsScopeDetection = { false },
                )
                val tiered = extras.plan("system", "", maxInputTokens = random.nextInt(20, 4_000), diffTokensWanted = 500)

                assertTrue(
                    tiered.skipReason != null || text in tiered.combinedExtra.orEmpty(),
                    "round $round: learnings cut without a skip"
                )
            }
        }

    @Test
    fun `the learnings file is read from the base by default and not when disabled`() {
        val ext = AiPipelineExtension(registry)

        assertTrue(LearningsConfig.DEFAULT_FILE in ext.baseFiles(JsonObject(emptyMap())))
        val off = buildJsonObject { put("learnings", buildJsonObject { put("enabled", JsonPrimitive(false)) }) }
        assertFalse(LearningsConfig.DEFAULT_FILE in ext.baseFiles(off))
    }

    @Test
    fun `a custom learnings file replaces the default one and its entries are applied`() =
        runTest {
            val custom = "team/learnings.yaml"
            val ext = AiPipelineExtension(registry)
            val bare = buildJsonObject { put("learnings", buildJsonObject { put("file", JsonPrimitive(custom)) }) }
            val config = buildJsonObject { put("ai", bare) }
            val mr = createTestMergeRequest(changes = changes)
            val file = "learnings:\n  - rule: noisy-rule\n    action: suppress\n    evidence: {rejected: 6, accepted: 0}\n"
            val findings = listOf(finding("noisy-rule"), finding("sharp-rule"))

            assertTrue(custom in ext.baseFiles(bare))
            assertFalse(LearningsConfig.DEFAULT_FILE in ext.baseFiles(bare))

            ext.onConfigured(PipelineEnvironment(aiService = null, rawConfig = config, statsDir = statsDir()))
            ext.onBaseFiles(mapOf(LearningsConfig.DEFAULT_FILE to file))
            assertEquals(listOf("noisy-rule", "sharp-rule"), ext.afterValidation(findings, mr, config).map { it.rule })
            ext.onBaseFiles(mapOf(custom to file))
            assertEquals(listOf("sharp-rule"), ext.afterValidation(findings, mr, config).map { it.rule })
        }

    @Test
    fun `learnings read from the base drop matching findings after validation and do not leak into the next run`() =
        runTest {
            val ext = AiPipelineExtension(registry)
            val config = JsonObject(emptyMap())
            val mr = createTestMergeRequest(changes = changes)
            val file = "learnings:\n  - rule: noisy-rule\n    action: suppress\n    evidence: {rejected: 6, accepted: 0}\n"
            val findings = listOf(finding("noisy-rule"), finding("sharp-rule"))

            ext.onConfigured(PipelineEnvironment(aiService = null, rawConfig = config, statsDir = statsDir()))
            ext.onBaseFiles(mapOf(LearningsConfig.DEFAULT_FILE to file))
            assertEquals(listOf("sharp-rule"), ext.afterValidation(findings, mr, config).map { it.rule })

            ext.onConfigured(PipelineEnvironment(aiService = null, rawConfig = config, statsDir = statsDir()))
            assertEquals(listOf("noisy-rule", "sharp-rule"), ext.afterValidation(findings, mr, config).map { it.rule })
        }

    private fun statsDir() = "build/tmp/karandash-learnings-${Random.nextLong().toULong()}"

    private val registry: ModuleRegistry = object : ModuleRegistry by buildTestRegistry() {
        override fun configFormats(): List<ConfigFormatModule> = listOf(YamlFormatModule())
    }

    private val changes = listOf(FileChange("src/App.kt", ChangeType.MODIFIED, 1, 0, diffContent = "+a"))

    private companion object {
        const val SEED = 15
        const val ROUNDS = 30
    }
}
