package io.karandash.plugins.ai.core.rules

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AiContextQualityConfigTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `default config preserves historical behaviour`() {
        val config = AiContextQualityConfig()
        assertTrue(config.enabled)
        assertEquals(TruncationStrategy.SEQUENTIAL, config.truncation.strategy)
        assertEquals(TokenEstimator.CHARS4, config.truncation.tokenEstimator)
        assertTrue(config.truncation.preserveHunkBoundaries)
        assertEquals(false, config.relatedFiles.enabled)
        assertEquals(false, config.fullFileContext.enabled)
        assertTrue(config.conventions.autoDetect)
        assertTrue(config.findingValidator.enabled)
        assertTrue(config.findingValidator.strict)
        assertTrue(config.ruleContext.isEmpty())
    }

    @Test
    fun `priority weights must sum to 1`() {
        assertFailsWith<IllegalArgumentException> {
            TruncationPriorityConfig(
                changeVolumeWeight = 0.5,
                fileTypeWeight = 0.5,
                hotspotWeight = 0.5,
                pathPatternWeight = 0.0,
            )
        }
    }

    @Test
    fun `priority weights accept small rounding error`() {
        // 0.29 + 0.29 + 0.21 + 0.21 = 1.00 - at the lower bound of 0.99
        TruncationPriorityConfig(
            changeVolumeWeight = 0.29,
            fileTypeWeight = 0.29,
            hotspotWeight = 0.21,
            pathPatternWeight = 0.21,
        )
    }

    @Test
    fun `negative priority weight rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TruncationPriorityConfig(
                changeVolumeWeight = -0.1,
                fileTypeWeight = 0.4,
                hotspotWeight = 0.35,
                pathPatternWeight = 0.35,
            )
        }
    }

    @Test
    fun `FileTypeTiers classifies kotlin as HIGH`() {
        val tiers = FileTypeTiers()
        assertEquals(FileTier.HIGH, tiers.tierFor("src/main/kotlin/Auth.kt"))
        assertEquals(FileTier.HIGH, tiers.tierFor("app/src/main/java/App.java"))
    }

    @Test
    fun `FileTypeTiers classifies yaml as MEDIUM`() {
        val tiers = FileTypeTiers()
        assertEquals(FileTier.MEDIUM, tiers.tierFor("config/application.yml"))
    }

    @Test
    fun `FileTypeTiers classifies markdown and lock as LOW`() {
        val tiers = FileTypeTiers()
        assertEquals(FileTier.LOW, tiers.tierFor("README.md"))
        assertEquals(FileTier.LOW, tiers.tierFor("yarn.lock"))
    }

    @Test
    fun `FileTypeTiers falls back to MEDIUM for unknown`() {
        val tiers = FileTypeTiers()
        assertEquals(FileTier.MEDIUM, tiers.tierFor("mystery.xyz"))
    }

    @Test
    fun `maxFilesToAnalyze range enforced`() {
        assertFailsWith<IllegalArgumentException> { AiContextQualityConfig(maxFilesToAnalyze = 0) }
        assertFailsWith<IllegalArgumentException> { AiContextQualityConfig(maxFilesToAnalyze = 600) }
    }

    @Test
    fun `RelatedFilesConfig rejects out-of-range depth`() {
        assertFailsWith<IllegalArgumentException> { RelatedFilesConfig(depth = 0) }
        assertFailsWith<IllegalArgumentException> { RelatedFilesConfig(depth = 10) }
    }

    @Test
    fun `FullFileContextConfig rejects invalid caps`() {
        assertFailsWith<IllegalArgumentException> { FullFileContextConfig(maxFileLines = 0) }
        assertFailsWith<IllegalArgumentException> { FullFileContextConfig(maxFileLines = 5_000) }
        assertFailsWith<IllegalArgumentException> { FullFileContextConfig(maxFiles = 0) }
        assertFailsWith<IllegalArgumentException> { FullFileContextConfig(maxFiles = 100) }
    }

    @Test
    fun `ConventionsConfig rejects huge max-chars`() {
        assertFailsWith<IllegalArgumentException> { ConventionsConfig(maxChars = 30_000) }
    }

    @Test
    fun `serialization uses kebab case keys`() {
        val cfg = AiContextQualityConfig(
            truncation = TruncationConfig(strategy = TruncationStrategy.PRIORITY),
            maxFilesToAnalyze = 50,
        )
        val encoded = json.encodeToString(AiContextQualityConfig.serializer(), cfg)
        assertTrue(encoded.contains("\"max-files-to-analyze\":50"))
        assertTrue(encoded.contains("\"strategy\":\"priority\""))
    }

    @Test
    fun `serialization round trip preserves all fields`() {
        val original = AiContextQualityConfig(
            enabled = true,
            truncation = TruncationConfig(
                strategy = TruncationStrategy.PRIORITY,
                tokenEstimator = TokenEstimator.CHARS3,
                preserveHunkBoundaries = false,
                priority = TruncationPriorityConfig(
                    changeVolumeWeight = 0.4,
                    fileTypeWeight = 0.3,
                    hotspotWeight = 0.2,
                    pathPatternWeight = 0.1,
                ),
                budgetAllocation = BudgetAllocation.EQUAL,
            ),
            fullFileContext = FullFileContextConfig(enabled = true, mode = FullFileMode.BOTH),
            ruleContext = mapOf(
                "review-comments" to PerRuleContextConfig(
                    relatedFiles = RelatedFilesConfig(enabled = false),
                ),
            ),
        )
        val encoded = json.encodeToString(AiContextQualityConfig.serializer(), original)
        val decoded = json.decodeFromString(AiContextQualityConfig.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `deserialization accepts missing fields and uses defaults`() {
        val decoded = json.decodeFromString(AiContextQualityConfig.serializer(), "{}")
        assertEquals(AiContextQualityConfig(), decoded)
    }

    @Test
    fun `PerRuleContextConfig allows partial overrides`() {
        val cfg = PerRuleContextConfig(relatedFiles = RelatedFilesConfig(enabled = false))
        assertEquals(null, cfg.truncation)
        assertEquals(null, cfg.fullFileContext)
        assertEquals(false, cfg.relatedFiles?.enabled)
    }
}
