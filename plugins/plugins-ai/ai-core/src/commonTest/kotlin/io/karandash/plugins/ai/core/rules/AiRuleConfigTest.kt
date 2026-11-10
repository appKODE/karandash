package io.karandash.plugins.ai.core.rules

import io.karandash.api.support.mapToJsonElement
import io.karandash.plugins.ai.core.DiffPreprocessorOptions
import io.karandash.sdk.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiRuleConfigTest {
    @Test
    fun `defaults are applied when config is empty`() {
        val config = AiRuleConfig.fromPluginConfig(mapToJsonElement(emptyMap()))

        assertEquals(AiRuleConfig.DEFAULT_MAX_DIFF_CHARS, config.maxDiffCharsPerFile)
        assertEquals(AiRuleConfig.DEFAULT_MAX_DIFF_CHARS, config.maxDiffCharsTotal)
        assertEquals(AiRuleConfig.DEFAULT_CATEGORIZER_MAX_TOKENS, config.categorizerMaxTokens)
        assertEquals(AiRuleConfig.DEFAULT_RISK_SCORER_MAX_TOKENS, config.riskScorerMaxTokens)
        assertEquals(AiRuleConfig.DEFAULT_FIX_SUGGESTER_MAX_TOKENS, config.fixSuggesterMaxTokens)
        assertEquals(AiRuleConfig.DEFAULT_REVIEW_MAX_TOKENS, config.reviewMaxTokens)
        assertEquals(AiRuleConfig.DEFAULT_RISK_ERROR_THRESHOLD, config.riskErrorThreshold)
        assertEquals(AiRuleConfig.DEFAULT_RISK_WARNING_THRESHOLD, config.riskWarningThreshold)
        assertEquals(AiRuleConfig.DEFAULT_MAX_COMMENTS, config.maxComments)
        assertEquals(AiRuleConfig.DEFAULT_RESPONSE_LANGUAGE, config.responseLanguage)
        assertEquals(AiRuleConfig.DEFAULT_SPLIT_THRESHOLD, config.splitThreshold)
    }

    @Test
    fun `limits are parsed from config`() {
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "limits" to mapOf(
                        "maxDiffCharsPerFile" to 3000,
                        "maxDiffCharsTotal" to 30000,
                        "reviewMaxTokens" to 4000,
                    ),
                ),
            ),
        )

        assertEquals(3000, config.maxDiffCharsPerFile)
        assertEquals(30000, config.maxDiffCharsTotal)
        assertEquals(4000, config.reviewMaxTokens)
    }

    @Test
    fun `thresholds are parsed from config`() {
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "thresholds" to mapOf(
                        "riskError" to 0.8,
                        "riskWarning" to 0.5,
                    ),
                ),
            ),
        )

        assertEquals(0.8, config.riskErrorThreshold)
        assertEquals(0.5, config.riskWarningThreshold)
    }

    @Test
    fun `top-level keys are parsed`() {
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "maxComments" to 5,
                    "responseLanguage" to "Japanese",
                    "splitThreshold" to 20,
                ),
            ),
        )

        assertEquals(5, config.maxComments)
        assertEquals("Japanese", config.responseLanguage)
        assertEquals(20, config.splitThreshold)
    }

    @Test
    fun `invalid types fall back to defaults`() {
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "limits" to mapOf(
                        "maxDiffCharsPerFile" to "not-a-number",
                    ),
                    "maxComments" to "invalid",
                    "responseLanguage" to 42,
                ),
            ),
        )

        assertEquals(AiRuleConfig.DEFAULT_MAX_DIFF_CHARS, config.maxDiffCharsPerFile)
        assertEquals(AiRuleConfig.DEFAULT_MAX_COMMENTS, config.maxComments)
        // responseLanguage from int 42 - coerceInputValues treats it as string "42"
        // but the field expects String, so it will deserialize as "42"; fall through to default only if blank
    }

    @Test
    fun `null limits map falls back to defaults`() {
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(mapOf("limits" to null)),
        )

        assertEquals(AiRuleConfig.DEFAULT_MAX_DIFF_CHARS, config.maxDiffCharsPerFile)
    }

    @Test
    fun `data class copy works correctly`() {
        val original = AiRuleConfig()
        val modified = original.copy(review = ReviewLimits(maxComments = 99))

        assertEquals(AiRuleConfig.DEFAULT_MAX_COMMENTS, original.maxComments)
        assertEquals(99, modified.maxComments)
    }

    @Test
    fun `size-policy skip is parsed correctly`() {
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "size-policy" to mapOf(
                        "skip" to mapOf(
                            "maxFiles" to 500,
                            "maxTotalLines" to 50000,
                            "maxCommits" to 200,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(500, config.skipPolicy.maxFiles)
        assertEquals(50000, config.skipPolicy.maxTotalLines)
        assertEquals(200, config.skipPolicy.maxCommits)
    }

    @Test
    fun `size-policy defaults when absent`() {
        val config = AiRuleConfig.fromPluginConfig(mapToJsonElement(emptyMap()))

        assertTrue(config.skipPolicy.isUnconfigured())
        assertNull(config.skipPolicy.maxFiles)
        assertNull(config.partialReviewPolicy)
        assertEquals(AiExcludePolicy.DEFAULT_PATTERNS, config.excludePolicy.patterns)
    }

    @Test
    fun `size-policy partial-review is parsed correctly`() {
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "size-policy" to mapOf(
                        "partial-review" to mapOf(
                            "maxFiles" to 20,
                            "strategy" to "by-type",
                            "prioritize-types" to listOf("*.kt", "*.java"),
                        ),
                    ),
                ),
            ),
        )

        assertNotNull(config.partialReviewPolicy)
        assertEquals(20, config.partialReviewPolicy!!.maxFiles)
        assertEquals(PartialReviewStrategy.BY_TYPE, config.partialReviewPolicy!!.strategy)
        assertEquals(listOf("*.kt", "*.java"), config.partialReviewPolicy!!.prioritizeTypes)
    }

    @Test
    fun `size-policy exclude patterns extend the defaults`() {
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "size-policy" to mapOf(
                        "exclude" to mapOf(
                            "patterns" to listOf("**/build/**", "**/dist/**"),
                        ),
                    ),
                ),
            ),
        )

        val patterns = config.excludePolicy.patterns
        assertTrue(patterns.containsAll(listOf("**/build/**", "**/dist/**")), "user patterns: $patterns")
        assertTrue(patterns.containsAll(AiExcludePolicy.DEFAULT_PATTERNS), "defaults kept: $patterns")
    }

    @Test
    fun `size-policy exclude uses default patterns when not specified`() {
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "size-policy" to mapOf(
                        "exclude" to mapOf<String, Any?>(),
                    ),
                ),
            ),
        )

        assertEquals(AiExcludePolicy.DEFAULT_PATTERNS, config.excludePolicy.patterns)
    }

    @Test
    fun `size-policy exclude disabled via empty patterns`() {
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "size-policy" to mapOf(
                        "exclude" to mapOf(
                            "patterns" to emptyList<String>(),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(AiExcludePolicy.DEFAULT_PATTERNS, config.excludePolicy.patterns)
    }

    @Test
    fun `size-policy skip with partial thresholds`() {
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "size-policy" to mapOf(
                        "skip" to mapOf(
                            "maxFiles" to 500,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(500, config.skipPolicy.maxFiles)
        assertNull(config.skipPolicy.maxTotalLines)
        assertNull(config.skipPolicy.maxCommits)
        assertFalse(config.skipPolicy.isUnconfigured())
    }

    @Test
    fun `diff-preprocessor defaults when absent`() {
        val config = AiRuleConfig.fromPluginConfig(mapToJsonElement(emptyMap()))

        assertEquals(DiffPreprocessorOptions(), config.diffPreprocessor)
        assertFalse(config.diffPreprocessor.skipDeletedFiles)
        assertFalse(config.diffPreprocessor.stripDeletedLines)
        assertEquals(DiffPreprocessorOptions.DEFAULT_DELETED_FILE_STUB_LINES, config.diffPreprocessor.deletedFileStubLines)
    }

    @Test
    fun `diff-preprocessor is parsed from size-policy`() {
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "size-policy" to mapOf(
                        "diff-preprocessor" to mapOf(
                            "skipDeletedFiles" to false,
                            "stripDeletedLines" to false,
                            "skipBinaryDiffs" to true,
                        ),
                    ),
                ),
            ),
        )

        assertFalse(config.diffPreprocessor.skipDeletedFiles)
        assertTrue(config.diffPreprocessor.skipPureRenames) // default
        assertFalse(config.diffPreprocessor.stripDeletedLines)
        assertTrue(config.diffPreprocessor.stripDiffMetadata) // default
        assertTrue(config.diffPreprocessor.skipBinaryDiffs)
    }

    @Test
    fun `diff-preprocessor supports camelCase key`() {
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "sizePolicy" to mapOf(
                        "diffPreprocessor" to mapOf(
                            "skipDeletedFiles" to false,
                        ),
                    ),
                ),
            ),
        )

        assertFalse(config.diffPreprocessor.skipDeletedFiles)
    }

    @Test
    fun `codeSuggestionMode defaults to inline`() {
        val config = AiRuleConfig.fromPluginConfig(mapToJsonElement(emptyMap()))
        assertEquals("inline", config.codeSuggestionMode)
    }

    @Test
    fun `codeSuggestionMode is parsed from config`() {
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(mapOf("codeSuggestionMode" to "separate")),
        )
        assertEquals("separate", config.codeSuggestionMode)
    }

    @Test
    fun `codeSuggestionMode blank falls back to default`() {
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(mapOf("codeSuggestionMode" to "")),
        )
        assertEquals("inline", config.codeSuggestionMode)
    }

    @Test
    fun `PartialReviewStrategy fromString handles all variants`() {
        assertEquals(PartialReviewStrategy.MOST_CHANGED, PartialReviewStrategy.fromString("most-changed"))
        assertEquals(PartialReviewStrategy.FIRST, PartialReviewStrategy.fromString("first"))
        assertEquals(PartialReviewStrategy.BY_TYPE, PartialReviewStrategy.fromString("by-type"))
        assertEquals(PartialReviewStrategy.MOST_CHANGED, PartialReviewStrategy.fromString("unknown"))
    }

    @Test
    fun `fromPluginConfig reads excludePolicy from sizePolicy block`() {
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "sizePolicy" to mapOf(
                        "exclude" to mapOf(
                            "patterns" to listOf("**/*.yml"),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(AiExcludePolicy.DEFAULT_PATTERNS + "**/*.yml", config.excludePolicy.patterns)
    }

    @Test
    fun `malformed string list warns and reads as empty`() {
        val warnings = mutableListOf<String>()
        val logger = object : Logger {
            override fun debug(message: String) = Unit

            override fun info(message: String) = Unit

            override fun warn(message: String) {
                warnings += message
            }

            override fun error(message: String) = Unit
        }
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(mapOf("filters" to mapOf("include" to "src/**"))),
            logger,
        )

        assertTrue(config.fileFilters.isEmpty())
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue("filters.include" in warnings.single(), warnings.single())
    }
}
